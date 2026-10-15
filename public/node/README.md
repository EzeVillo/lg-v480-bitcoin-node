# Componentes del nodo

Los archivos son fuentes y ejemplos. No incluyen bases de datos, wallets,
cookies RPC, claves Tor ni configuración copiada del dispositivo.

## Configuración propia

Las plantillas `bitcoin.conf.example` y `torrc.example` contienen marcadores
`@LOOPBACK_IP@` y `@TERMUX_HOME@`. Sustituirlos localmente por la dirección
numérica de loopback del equipo y el directorio HOME de Termux. No usarlas sin
adaptar. La app utiliza `localhost`; los servicios deben escuchar en loopback.

Guardar la configuración de Core en `~/.bitcoin/bitcoin.conf` y la de Tor en
la ruta que utilice su servicio. Restringir ambos archivos al usuario de Termux.
El RPC local usa la cookie que genera Core; no se incluyen usuarios ni
contraseñas. Para Sparrow, generar autenticación propia con la herramienta
[rpcauth de Bitcoin Core](https://github.com/bitcoin/bitcoin/tree/master/share/rpcauth)
y conservarla únicamente en la configuración local.

La plantilla deshabilita la wallet. Si se requiere una wallet de observación,
configurarla por separado sin publicar descriptores, xpubs ni nombres propios.
El servicio onion P2P genera su identidad localmente al iniciar Tor; el ejemplo
no contiene una identidad ni un servicio administrativo onion.
Para anunciar la identidad propia a pares, preparar localmente el argumento
`-externalip` leyendo el archivo `hostname` generado por Tor; no incorporarlo
a un script público. Las opciones de red de la plantilla se basan en la
[documentación Tor de Core 31](https://github.com/bitcoin/bitcoin/blob/v31.0/doc/tor.md).
La creación del servicio se describe en la
[guía oficial de Tor](https://community.torproject.org/onion-services/setup/).

El directorio de datos de Core se debe pasar a `bitcoind` y `bitcoin-cli` con
`-datadir`, manteniendo la misma ruta en la API. No guardar la cadena en Git.

## API CGI

Copiar `node.env.example` a `~/.config/v480/node.env` dentro de Termux y adaptar
`NODE_DATA` a la ubicación real de los datos de Core. Este archivo se carga como
script de shell: solo debe poder modificarlo el usuario de Termux.

```sh
mkdir -p ~/.config/v480
chmod 700 ~/.config/v480
chmod 600 ~/.config/v480/node.env
```

Sin esa configuración, la API consulta el directorio interno `~/.bitcoin`.
Instalar `api.cgi` como `cgi-bin/api`, con permiso de ejecución, bajo la raíz
HTTP propia. BusyBox HTTPD debe escuchar solo en loopback, puerto 8080. No
servir el repositorio completo ni el directorio de datos de Core por HTTP.
Mantener el endpoint local: devuelve estado y detalles del nodo sin
autenticación HTTP.

La API usa `bitcoin-cli`, `sv`, `awk`, `base64`, `tr`, `df`, `date`, `pidof` y
herramientas habituales de Termux/Android. Busca los servicios runit `tor` y
`bitcoind`. Para mostrar la identidad P2P busca el archivo `hostname` en
`~/.tor/bitcoin-service`, `~/.tor/bitcoin` o `~/.tor/hidden_service`.

## Supervisión y arranque

El kit conservado no contiene los antiguos `install.sh`, scripts `run`, ni los
comandos `node-start`/`node-stop`. Hay que preparar esa supervisión antes de usar
los botones de la app. Las fuentes oficiales describen
[termux-services](https://github.com/termux/termux-services) y
[Termux:Boot](https://github.com/termux/termux-boot).

La instalación documentada esperaba conectividad antes de Tor, y después la
microSD escribible, Tor listo y el hostname P2P antes de Core. Una parada debe
permitir que Core cierre sus bases de datos limpiamente. Comprobar localmente
reinicio, recuperación de Wi-Fi, API y cierre antes de dejar el nodo funcionando.

## Ajustes opcionales

- `set-low-memory-cache.sh`: ajusta `dbcache` a 32 o 64 MiB y conserva un respaldo.
  Requiere reinicio ordenado de Core y presupone `~/.bitcoin/bitcoin.conf`.
- `set-android-low-ram.sh`: aplica o restaura el perfil en `/system/build.prop`;
  requiere root temporal y la ROM compatible utilizada en este proyecto.
- `restore-android-desktop.sh`: vuelve a habilitar escritorio y teclado.
- `charge-control.c`: controlador de carga entre 50 % y 70 %.
  `build-charge-control.ps1` necesita Zig en PATH o `ZIG_EXE` y genera un binario
  ARM excluido de Git. `charge-control.rc` es una referencia para Android init;
  la instalación requiere adaptar permisos y SELinux al equipo.

Estos ajustes no son necesarios para compilar la app. Los resultados históricos
y sus límites están en el [README general](../../README.md).
