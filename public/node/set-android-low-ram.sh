#!/system/bin/sh
# Ejecutar mediante ADB root temporal. No modifica swap/zram ni los umbrales LMK.
# Uso: sh set-android-low-ram.sh apply|restore
set -eu
mode=${1:-}
case "$mode" in apply|restore) ;; *) echo 'Usar apply o restore' >&2; exit 1 ;; esac
[ "$(id -u)" = 0 ] || { echo 'Requiere ADB root temporal' >&2; exit 1; }
conf=/system/build.prop
backup=/data/local/tmp/v480-build.prop.before-lowram
pending=/data/local/tmp/v480-build.prop.pending
umask 077
if [ "$mode" = apply ]; then
    if [ ! -e "$backup" ]; then cp -p "$conf" "$backup"; chmod 600 "$backup"; fi
    sed '/^ro.config.low_ram=/d' "$conf" > "$pending"
    printf '\n# V480: perfil de memoria para panel dedicado\nro.config.low_ram=true\n' >> "$pending"
else
    [ -s "$backup" ] || { echo 'No existe el respaldo original' >&2; exit 1; }
    cp "$backup" "$pending"
fi
trap 'rm -f "$pending"; mount -o remount,ro /system' EXIT
mount -o remount,rw /system
# Conservar inode, propietario, permisos y etiqueta SELinux del archivo original.
cat "$pending" > "$conf"
cmp "$pending" "$conf"
sync
echo 'Configuracion guardada. Detener Core limpiamente antes de reiniciar Android.'
