# Preserving scripts from the older GhostHand APK

The first CI builds used an automatically generated Android debug certificate. New CI builds use a persistent certificate so future APKs can update in place. Because the signing certificates differ, Android may require a one-time uninstall/reinstall when moving from an old build to the persistent-signing build.

**Do not uninstall the old GhostHand app until you have exported its scripts.**

## Requirements

- Windows PowerShell
- Android SDK Platform-Tools (`adb`) installed and available on PATH
- Galaxy Z Fold 7 connected by USB
- USB debugging enabled and the computer authorized
- The old, debuggable GhostHand package still installed (`com.ghosthand`)

## Export

From the GhostHand repository folder, open PowerShell and run:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\tools\backup-legacy-scripts.ps1 -OutputPath "$PWD\GhostHand-legacy-backup.json"
```

The helper reads only the app's private `files/scripts` folder through Android's `run-as` facility. It does not change app data, stop the game, or uninstall GhostHand. It creates a portable JSON backup compatible with the new app's Restore Scripts action.

If the script reports that `run-as` is unavailable, **leave the old installation in place**. Do not uninstall it; the backup has not completed.

## Restore after installing the persistent-signing build

1. Install the new GhostHand APK.
2. Open GhostHand.
3. Choose **RESTORE SCRIPTS**.
4. Select `GhostHand-legacy-backup.json`.
5. Confirm that your saved runs appear in the list before deleting the backup file.

The helper has not been exercised on a physical Fold 7 in this repository's CI. CI validates compilation and unit tests; a real device is still needed to confirm USB/ADB access on the phone.
