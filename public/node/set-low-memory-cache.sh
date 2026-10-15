#!/data/data/com.termux/files/usr/bin/sh
# Uso: sh set-low-memory-cache.sh [32|64]. Requiere reinicio ordenado de Core.
set -eu
export PATH=/data/data/com.termux/files/usr/bin:/system/bin
umask 077
cache_mib=${1:-32}
case "$cache_mib" in 32|64) ;; *) echo 'Usar 32 o 64 MiB' >&2; exit 1 ;; esac
conf=/data/data/com.termux/files/home/.bitcoin/bitcoin.conf
backup="$conf.before-memory-tuning"
pending="$conf.memory-tuning.$$"
trap 'rm -f "$pending"' EXIT
if [ "$(grep -c '^dbcache=' "$conf")" != 1 ]; then
  echo 'Se esperaba una sola línea dbcache; no se modificó el archivo.' >&2
  exit 1
fi
if [ ! -e "$backup" ]; then cp -p "$conf" "$backup"; fi
sed "s/^dbcache=.*/dbcache=$cache_mib/" "$conf" > "$pending"
chmod 600 "$pending"
mv "$pending" "$conf"
printf 'dbcache=%s MiB. Reiniciar sólo el servicio bitcoind de forma ordenada.\n' "$cache_mib"
