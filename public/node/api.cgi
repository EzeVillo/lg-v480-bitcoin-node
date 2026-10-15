#!/data/data/com.termux/files/usr/bin/sh

node_prefix='/data/data/com.termux/files/usr'
node_home='/data/data/com.termux/files/home'
if [ -r "$node_home/.config/v480/node.env" ]; then
  . "$node_home/.config/v480/node.env"
fi
node_data="${NODE_DATA:-$node_home/.bitcoin}"
bitcoin_cli="$node_prefix/bin/bitcoin-cli"
task_tmp="${TMPDIR:-$node_prefix/tmp}/v480-panel.$$"

export PATH="$node_prefix/bin:$node_prefix/bin/applets:/system/bin:/system/xbin"
export LD_LIBRARY_PATH="$node_prefix/lib"

printf 'Content-Type: application/json; charset=utf-8\r\n'
printf 'Cache-Control: no-store, no-cache, must-revalidate\r\n'
printf 'Pragma: no-cache\r\n\r\n'

cleanup() {
  rm -rf "$task_tmp"
}
trap cleanup EXIT HUP INT TERM

json_string() {
  # Todos los valores habituales son ASCII simple: printf es interno de dash.
  # Para texto arbitrario, awk preserva UTF-8 y escapa todos los controles.
  # No dividir UTF-8 con patrones de sufijo de dash/bionic.
  case "$1" in
    *[!a-zA-Z0-9./:_\ -]*)
      JSON_STRING_VALUE="$1" awk 'BEGIN {
        for (i=1; i<32; i++) escape[sprintf("%c", i)]=sprintf("\\u%04x", i)
        escape[sprintf("%c", 127)]="\\u007f"
        value=ENVIRON["JSON_STRING_VALUE"]
        printf "\""
        for (i=1; i<=length(value); i++) {
          char=substr(value, i, 1)
          if (char == "\"" || char == "\\") printf "\\%s", char
          else if (char in escape) printf "%s", escape[char]
          else printf "%s", char
        }
        printf "\""
      }'
      ;;
    *) printf '"%s"' "$1" ;;
  esac
}

json_member() {
  printf '"%s":' "$1"
  json_string "$2"
  printf ','
}

case "&${QUERY_STRING:-}&" in
*'&detail=1&'*)
  detail="$($bitcoin_cli -datadir="$node_data" -rpcclienttimeout=8 -getinfo 2>&1)"
  detail_b64="$(printf '%s\n' "$detail" | base64 | tr -d '\r\n')"
  printf '{"detail_b64":"%s"}\n' "$detail_b64"
  exit 0
;;
esac

mkdir -p "$task_tmp"

chain_ready=0
if "$bitcoin_cli" -datadir="$node_data" -rpcclienttimeout=8 getblockchaininfo >"$task_tmp/chain" 2>/dev/null; then
  chain_ready=1
  "$bitcoin_cli" -datadir="$node_data" -rpcclienttimeout=8 getnetworkinfo >"$task_tmp/network" 2>/dev/null
  "$bitcoin_cli" -datadir="$node_data" -rpcclienttimeout=8 getmempoolinfo >"$task_tmp/mempool" 2>/dev/null
else
  : >"$task_tmp/network"
  : >"$task_tmp/mempool"
fi

if [ "$chain_ready" = 1 ]; then
  bitcoin_state='activo'
elif pidof bitcoind >/dev/null 2>&1 || ps -A 2>/dev/null | grep -q '[b]itcoind'; then
  bitcoin_state='iniciando'
else
  bitcoin_state='detenido'
fi

case "$(sv status "$node_prefix/var/service/tor" 2>/dev/null)" in
  run:*) tor_state='activo' ;;
  *) tor_state='inactivo' ;;
esac

wifi_state='desconectado'
read -r wifi_operstate 2>/dev/null </sys/class/net/wlan0/operstate || wifi_operstate=''
if [ "$wifi_operstate" = 'up' ] \
  || awk '$1 == "wlan0" { found=1 } END { exit !found }' /proc/net/route 2>/dev/null; then
  wifi_state='conectado'
fi

# bitcoin-cli imprime los campos de primer nivel con dos espacios. Leemos
# las tres respuestas una sola vez, sin pipelines por cada campo ni eval.
# Sólo aceptamos las claves conocidas; ignoramos objetos/arrays anidados.
while IFS= read -r rpc_field; do
  rpc_value=${rpc_field#*=}
  case ${rpc_field%%=*} in
    chain.blocks) blocks=$rpc_value ;;
    chain.headers) headers=$rpc_value ;;
    chain.verificationprogress) verification_progress=$rpc_value ;;
    chain.initialblockdownload) initial_block_download=$rpc_value ;;
    chain.time) last_block_time=$rpc_value ;;
    chain.chain) chain=$rpc_value ;;
    chain.pruned) pruned=$rpc_value ;;
    chain.pruneheight) prune_height=$rpc_value ;;
    chain.prune_target_size) prune_target_bytes=$rpc_value ;;
    chain.size_on_disk) size_on_disk=$rpc_value ;;
    network.connections_in) connections_in=$rpc_value ;;
    network.connections_out) connections_out=$rpc_value ;;
    network.version) core_version=$rpc_value ;;
    network.subversion) subversion=$rpc_value ;;
    mempool.size) mempool_size=$rpc_value ;;
    mempool.bytes) mempool_bytes=$rpc_value ;;
    mempool.maxmempool) mempool_max=$rpc_value ;;
  esac
done <<RPC_FIELDS
$(awk '
  /^  "[a-z_]+": / {
    key=$0; sub(/^  "/, "", key); sub(/".*/, "", key)
    value=$0; sub(/^  "[a-z_]+": /, "", value); sub(/,[[:space:]]*$/, "", value)
    sub(/^"/, "", value); sub(/"$/, "", value)
    source=FILENAME; sub(/^.*\//, "", source)
    print source "." key "=" value
  }
' "$task_tmp/chain" "$task_tmp/network" "$task_tmp/mempool")
RPC_FIELDS
core_service="$(sv status "$node_prefix/var/service/bitcoind" 2>/dev/null)"
core_uptime=0
case "$core_service" in
  run:*)
    core_service=${core_service%%;*}
    core_uptime=${core_service##* }
    core_uptime=${core_uptime%s}
    case "$core_uptime" in ''|*[!0-9]*) core_uptime=0 ;; esac
    ;;
esac

blocks="${blocks:-0}"
headers="${headers:-0}"
verification_progress="${verification_progress:-0}"
initial_block_download="${initial_block_download:-false}"
last_block_time="${last_block_time:-0}"
chain="${chain:-desconocida}"
pruned="${pruned:-false}"
prune_height="${prune_height:-0}"
prune_target_bytes="${prune_target_bytes:-0}"
size_on_disk="${size_on_disk:-0}"
connections_in="${connections_in:-0}"
connections_out="${connections_out:-0}"
core_version="${core_version:-0}"
mempool_size="${mempool_size:-0}"
mempool_bytes="${mempool_bytes:-0}"
mempool_max="${mempool_max:-0}"
core_uptime="${core_uptime:-0}"

memory_total_kib=0
memory_available_kib=''
memory_free=0
memory_buffers=0
memory_cached=0
memory_reclaimable=0
while read -r memory_key memory_value memory_unit; do
  case "$memory_key" in
    MemTotal:) memory_total_kib=$memory_value ;;
    MemAvailable:) memory_available_kib=$memory_value ;;
    MemFree:) memory_free=$memory_value ;;
    Buffers:) memory_buffers=$memory_value ;;
    Cached:) memory_cached=$memory_value ;;
    SReclaimable:) memory_reclaimable=$memory_value ;;
  esac
done </proc/meminfo
if [ -z "$memory_available_kib" ]; then
  memory_available_kib=$((memory_free + memory_buffers + memory_cached + memory_reclaimable))
fi
memory_used_kib=$((memory_total_kib - memory_available_kib))
if [ "$memory_used_kib" -lt 0 ]; then memory_used_kib=0; fi

storage_total_kib=0
storage_used_kib=0
storage_available=0
if [ -d "$node_data" ]; then
  storage_line="$(df -Pk "$node_data" 2>/dev/null | awk 'NR == 2 { print $2 " " $3 }')"
  read -r storage_total_kib storage_used_kib <<STORAGE
$storage_line
STORAGE
  storage_total_kib=${storage_total_kib:-0}
  storage_used_kib=${storage_used_kib:-0}
  if [ "$storage_total_kib" -gt 0 ]; then storage_available=1; fi
fi

read -r battery_percent 2>/dev/null </sys/class/power_supply/battery/capacity || battery_percent=0
read -r battery_temp_tenths 2>/dev/null </sys/class/power_supply/battery/temp || battery_temp_tenths=0
read -r battery_status 2>/dev/null </sys/class/power_supply/battery/status || battery_status='Unknown'
battery_percent="${battery_percent:-0}"
battery_temp_tenths="${battery_temp_tenths:-0}"
battery_status="${battery_status:-Unknown}"

read -r device_uptime device_idle 2>/dev/null </proc/uptime || device_uptime=0
device_uptime=${device_uptime%%.*}
device_uptime="${device_uptime:-0}"

ssh_connections="$(awk '$2 ~ /:1F56$/ && $4 == "01" { count++ } END { print count + 0 }' \
  /proc/net/tcp /proc/net/tcp6 2>/dev/null)"
ssh_connections="${ssh_connections:-0}"

onion=''
for onion_file in \
  "$node_home/.tor/bitcoin-service/hostname" \
  "$node_home/.tor/bitcoin/hostname" \
  "$node_home/.tor/hidden_service/hostname"
do
  if [ -r "$onion_file" ]; then
    read -r onion <"$onion_file"
    break
  fi
done
onion="${onion:-}"

updated_epoch="$(date +%s)"

printf '{'
json_member bitcoin "$bitcoin_state"
json_member tor "$tor_state"
json_member wifi "$wifi_state"
printf '"blocks":%s,' "$blocks"
printf '"headers":%s,' "$headers"
printf '"verification_progress":%s,' "$verification_progress"
printf '"initial_block_download":%s,' "$initial_block_download"
printf '"last_block_time":%s,' "$last_block_time"
json_member chain "$chain"
printf '"connections_in":%s,' "$connections_in"
printf '"connections_out":%s,' "$connections_out"
printf '"mempool_size":%s,' "$mempool_size"
printf '"mempool_bytes":%s,' "$mempool_bytes"
printf '"mempool_max":%s,' "$mempool_max"
printf '"core_uptime":%s,' "$core_uptime"
printf '"core_version":%s,' "$core_version"
json_member subversion "$subversion"
printf '"pruned":%s,' "$pruned"
printf '"prune_height":%s,' "$prune_height"
printf '"prune_target_bytes":%s,' "$prune_target_bytes"
printf '"size_on_disk":%s,' "$size_on_disk"
printf '"memory_total_kib":%s,' "$memory_total_kib"
printf '"memory_used_kib":%s,' "$memory_used_kib"
printf '"storage_total_kib":%s,' "$storage_total_kib"
printf '"storage_used_kib":%s,' "$storage_used_kib"
printf '"storage_available":%s,' "$storage_available"
printf '"battery_percent":%s,' "$battery_percent"
printf '"battery_temp_tenths":%s,' "$battery_temp_tenths"
json_member battery_status "$battery_status"
printf '"device_uptime":%s,' "$device_uptime"
printf '"ssh_connections":%s,' "$ssh_connections"
json_member onion "$onion"
printf '"updated_epoch":%s' "$updated_epoch"
printf '}\n'
