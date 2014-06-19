#!/bin/sh
rm -f ../bin/ApkRePermission.jar
cd ../tmp
jar cmfv ../src/MANIFEST.MF ../bin/ApkRePermission.jar jp
