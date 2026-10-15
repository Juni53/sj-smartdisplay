' 黒い窓を出さずに起動します（スタートアップに登録する用）。
Set sh = CreateObject("WScript.Shell")
dir = CreateObject("Scripting.FileSystemObject").GetParentFolderName(WScript.ScriptFullName)
sh.CurrentDirectory = dir
sh.Run "pythonw agent.py", 0, False
