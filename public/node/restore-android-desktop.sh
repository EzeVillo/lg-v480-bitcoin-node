#!/system/bin/sh
# Restaura escritorio y teclado sin borrar datos. Puede ejecutarse con adb shell.
set -eu
pm enable --user 0 com.cyanogenmod.trebuchet
pm enable --user 0 com.android.inputmethod.latin
ime enable com.android.inputmethod.latin/.LatinIME
ime set com.android.inputmethod.latin/.LatinIME
cmd package set-home-activity --user 0 com.cyanogenmod.trebuchet/com.android.launcher3.Launcher
am start -a android.intent.action.MAIN -c android.intent.category.HOME
