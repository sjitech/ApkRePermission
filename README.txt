Add/Remove permission to android app file (*.apk)

java -jar ApkRePermission <inputApkFile> <outputApkFile> <debugKeyStoreFile> [+some_permissions|-some_permission]...

Note: debugKeyStoreFile can be \"\" means do not sign result apk file