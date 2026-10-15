# App Android Nodo Bitcoin 1.16

Fuentes Java de la reconexión Wi-Fi, pantalla de mantenimiento y panel nativo.
Orientadas a Android 7, con API mínima 24 y target 25. No se comprobó compatibilidad
con Android moderno. La API se consulta en `http://localhost:8080/cgi-bin/api`.

## Compilar en Windows

Se necesitan JDK con `javac`, Android SDK Platform 36 y Build Tools 36.1.0.
No se necesita una cuenta, credencial RPC ni dirección del nodo para compilar.

Desde esta carpeta:

```powershell
.\build.ps1
```

Genera `build/wifi-autoconnect-v1.16-unsigned.apk` y muestra su SHA-256. La carpeta
`build` está excluida de Git. El APK sin firmar permite comprobar la compilación;
para instalarlo hace falta firmarlo con una clave propia.

```powershell
.\build.ps1 -KeystorePath "$env:USERPROFILE\.android\debug.keystore" -KeyAlias androiddebugkey
```

La herramienta de firma solicita la contraseña. También puede recibirla desde
la variable local `V480_KEYSTORE_PASSWORD`; no guardar su valor en scripts ni
en Git. El resultado firmado queda en `build/wifi-autoconnect-v1.16.apk` y el
script verifica su firma. Para distribución estable conviene una clave propia
de publicación. Una actualización exige la misma firma que el APK instalado.

El SDK puede indicarse con `-SdkRoot` y la versión de herramientas con
`-BuildToolsVersion`; el script usa la plataforma 36.

## Integración con Termux

Preparar la [API local](../node/README.md). Los botones ejecutan
`~/bin/node-start` y `~/bin/node-stop` de Termux mediante `RUN_COMMAND`.
Esos comandos requieren la configuración de servicios de cada instalación;
no están incluidos en este respaldo. Consultar la
[documentación de RUN_COMMAND](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent).

En Termux, habilitar `allow-external-apps=true` en `~/.termux/termux.properties`
y recargar la configuración. Después de instalar el APK firmado:

```sh
adb shell pm grant ar.eze.v480wifiautoconnect com.termux.permission.RUN_COMMAND
adb shell am start -n ar.eze.v480wifiautoconnect/.MainActivity
```

El panel puede elegirse como HOME:

```sh
adb shell cmd package set-home-activity --user 0 ar.eze.v480wifiautoconnect/.NativePanelActivity
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
```

El [script de restauración](../node/restore-android-desktop.sh) permite volver
a Trebuchet y LatinIME en la ROM utilizada. No hace falta deshabilitarlos para
probar el panel.

## Datos de la app

La app reutiliza redes Wi-Fi ya guardadas por Android; las fuentes no incluyen
SSID ni claves Wi-Fi. No incorpora credenciales RPC, claves de firma, direcciones
remotas ni datos de wallets. El panel puede mostrar información local obtenida
de la API; revisar las capturas antes de compartirlas.
