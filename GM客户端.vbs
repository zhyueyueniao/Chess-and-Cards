' Chess and Cards GM client launcher (pure ASCII, WSH-safe)
Set ws = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
currentDir = fso.GetParentFolderName(WScript.ScriptFullName)

exePub = currentDir & "\tools\CcGmClient\publish\CcGmClient.exe"
exeRel = currentDir & "\tools\CcGmClient\bin\Release\net10.0-windows\CcGmClient.exe"
exeDbg = currentDir & "\tools\CcGmClient\bin\Debug\net10.0-windows\CcGmClient.exe"

If fso.FileExists(exePub) Then
    ws.Run """" & exePub & """", 1, False
ElseIf fso.FileExists(exeRel) Then
    ws.Run """" & exeRel & """", 1, False
ElseIf fso.FileExists(exeDbg) Then
    ws.Run """" & exeDbg & """", 1, False
Else
    '"GM" + Chinese "tool/batch" + ".bat" built via ChrW to stay pure ASCII
    batName = "GM" & ChrW(24037) & ChrW(20855) & ".bat"
    ws.Run "cmd /c """ & currentDir & "\" & batName & """", 1, False
End If
