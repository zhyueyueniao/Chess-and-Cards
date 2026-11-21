import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

/**
 * 部署在 /lobby/ 子路径下，与同机其他站点共存。
 * 开发环境同样通过 /lobby 前缀代理到后端，保证路径一致。
 */
const BASE = '/lobby/';

// 后端地址可用环境变量覆盖，避免换机器就要改代码
const API_TARGET = process.env.VITE_API_TARGET ?? 'http://127.0.0.1:8080';
const WS_TARGET = process.env.VITE_WS_TARGET ?? 'ws://127.0.0.1:9000';

/** 计算 public/assets/music 下音频的内容哈希，彻底避免 Nginx 对 /lobby/assets/ 的 1 年 immutable 强缓存导致换歌不生效 */
function computeBgmHashes(): Record<string, string> {
  const files: Record<string, string> = {
    lounge: 'public/assets/music/menu-theme.mp3',
    chill: 'public/assets/music/upbeat-theme.mp3',
  };
  const out: Record<string, string> = {};
  for (const [id, rel] of Object.entries(files)) {
    try {
      const buf = fs.readFileSync(path.resolve(__dirname, rel));
      out[id] = crypto.createHash('sha256').update(buf).digest('hex').slice(0, 10);
    } catch {
      out[id] = String(Date.now());
    }
  }
  return out;
}

/** 构建版本号写入 index.html 的 <meta name="cc-build">，供 APP 前台恢复时检测前端资源是否有更新 */
const BUILD_ID = `${Date.now()}-${crypto.createHash('sha1').update(JSON.stringify({ at: Date.now(), rand: Math.random() })).digest('hex').slice(0, 8)}`;

export default defineConfig({
  plugins: [
    react(),
    {
      name: 'inject-build-version',
      transformIndexHtml(html) {
        return html.replace(
          '<meta name="theme-color" content="#0b0f1e" />',
          `<meta name="theme-color" content="#0b0f1e" />\n    <meta name="cc-build" content="${BUILD_ID}" />`,
        );
      },
    },
  ],
  base: BASE,
  define: {
    __BGM_HASH__: JSON.stringify(computeBgmHashes()),
  },
  server: {
    port: Number(process.env.VITE_PORT ?? 5173),
    proxy: {
      '/lobby/api': {
        target: API_TARGET,
        changeOrigin: true,
        rewrite: (p) => p.replace(/^\/lobby/, ''),
      },
      '/lobby/ws': {
        target: WS_TARGET,
        ws: true,
        rewrite: (p) => p.replace(/^\/lobby/, ''),
      },
    },
  },
  optimizeDeps: {
    exclude: ['@cc/protocol'],
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    // 各视图/玩法按需加载，CSS 也按 chunk 拆分
    cssCodeSplit: true,
  },
});
