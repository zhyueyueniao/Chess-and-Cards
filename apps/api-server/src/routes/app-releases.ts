import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { mkdir, readFile, readdir, rename, stat, unlink, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import type { FastifyInstance } from 'fastify';
import { httpError, requireAdmin } from '../http-utils';

/**
 * 手机 APP 更新包分发。
 *
 * 存储目录默认 /data/logs/app-releases —— 复用 api-server 已有的 /data/logs 宿主挂载，
 * 不新增 compose 卷，发版/回滚都不会丢失 APK。
 * 对外 URL 走 nginx 现有 /api/ 代理：
 *   GET  /api/app-release/manifest          客户端检查更新（无需鉴权）
 *   GET  /api/app-release/download/cc-N.apk 下载安装包（无需鉴权）
 *   POST /api/app-release/upload            上传新版本（ADMIN_TOKEN）
 */

const APK_PREFIX = 'cc-';
const APK_NAME_RE = /^cc-\d+\.apk$/;
const MAX_APK_BYTES = 64 * 1024 * 1024;

function releaseDir(): string {
  return process.env.APP_RELEASE_DIR || '/data/logs/app-releases';
}

interface Manifest {
  versionCode: number;
  versionName: string;
  apk: string;
  size: number;
  sha256: string;
  notes?: string;
}

async function readManifest(): Promise<Manifest | null> {
  try {
    const raw = await readFile(join(releaseDir(), 'manifest.json'), 'utf8');
    const parsed = JSON.parse(raw) as Manifest;
    return parsed && Number.isInteger(parsed.versionCode) ? parsed : null;
  } catch {
    return null;
  }
}

async function writeManifest(manifest: Manifest): Promise<void> {
  // 设备端 org.json 解析带 BOM 的 JSON 会直接抛异常，必须 UTF-8 无 BOM
  await writeFile(
    join(releaseDir(), 'manifest.json'),
    JSON.stringify(manifest),
    { encoding: 'utf8', flag: 'w' },
  );
}

export function registerAppReleaseRoutes(app: FastifyInstance): void {
  // 二进制 APK 上传：整包进内存后再落盘，路由级放宽 Fastify 默认 4MB 限制
  app.addContentTypeParser(
    'application/octet-stream',
    { parseAs: 'buffer' },
    (_req, body, done) => done(null, body),
  );

  app.get('/api/app-release/manifest', async (_req, reply) => {
    const manifest = await readManifest();
    if (!manifest) throw httpError('暂无更新包', 404);
    reply.header('Cache-Control', 'no-store, no-cache, must-revalidate');
    return manifest;
  });

  app.get<{ Params: { file: string } }>('/api/app-release/download/:file', async (req, reply) => {
    const name = req.params.file;
    if (!APK_NAME_RE.test(name)) throw httpError('非法文件名', 400);
    const path = join(releaseDir(), name);
    let info;
    try {
      info = await stat(path);
    } catch {
      throw httpError('更新包不存在', 404);
    }
    reply.header('Content-Type', 'application/vnd.android.package-archive');
    reply.header('Content-Disposition', `attachment; filename="${name}"`);
    reply.header('Cache-Control', 'no-store');
    reply.header('Content-Length', String(info.size));
    return reply.send(createReadStream(path));
  });

  app.post<{
    Querystring: {
      versionCode?: string;
      versionName?: string;
      force?: string;
      notes?: string;
      sha256?: string;
    };
    Body: Buffer;
  }>(
    '/api/app-release/upload',
    { bodyLimit: MAX_APK_BYTES },
    async (req) => {
      requireAdmin(req);
      const code = Number(req.query.versionCode);
      if (!Number.isInteger(code) || code < 1) {
        throw httpError('versionCode 必须是正整数', 400);
      }
      const force = req.query.force === 'true';
      const current = await readManifest();
      if (current && current.versionCode >= code && !force) {
        throw httpError(`已有更新包版本 ${current.versionCode}，新版本必须更大或使用 force=true`, 409);
      }

      const bytes = req.body;
      if (!Buffer.isBuffer(bytes) || bytes.length === 0) {
        throw httpError('请求体必须是 APK 二进制内容', 400);
      }
      if (bytes.length > MAX_APK_BYTES) {
        throw httpError('APK 超过 64MB 限制', 413);
      }
      // 简单校验 ZIP/APK 文件头，避免误传 HTML 错误页
      if (bytes[0] !== 0x50 || bytes[1] !== 0x4b) {
        throw httpError('文件内容不是 ZIP/APK', 400);
      }
      const sha256 = createHash('sha256').update(bytes).digest('hex');
      if (req.query.sha256 && req.query.sha256.toLowerCase() !== sha256) {
        throw httpError('sha256 与上传文件不一致', 400);
      }

      const dir = releaseDir();
      await mkdir(dir, { recursive: true });
      const name = `${APK_PREFIX}${code}.apk`;
      const target = join(dir, name);
      const tmp = join(dir, `.${name}.${process.pid}.tmp`);
      await writeFile(tmp, bytes);
      await rename(tmp, target);

      const manifest: Manifest = {
        versionCode: code,
        versionName: (req.query.versionName || `1.0.${code}`).slice(0, 40),
        apk: name,
        size: bytes.length,
        sha256,
      };
      if (req.query.notes) manifest.notes = req.query.notes.slice(0, 500);
      await writeManifest(manifest);

      // 只保留最近 5 个 APK
      const old = (await readdir(dir))
        .filter((f) => APK_NAME_RE.test(f) && f !== name)
        .sort((a, b) => {
          const ca = Number(a.slice(APK_PREFIX.length, -4));
          const cb = Number(b.slice(APK_PREFIX.length, -4));
          return cb - ca;
        })
        .slice(5);
      await Promise.all(old.map((f) => unlink(join(dir, f)).catch(() => undefined)));

      return { ok: true, ...manifest };
    },
  );
}
