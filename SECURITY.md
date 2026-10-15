# Privacidad al publicar

Solo se publican los archivos enumerados en `PUBLIC_FILES.txt`.
`.gitignore` aplica una lista explícita de archivos permitidos: una carpeta
nueva o un archivo nuevo no se incorpora por accidente con `git add .`.

## Qué queda fuera

- `device-backup/`, registros completos y documentos operativos del dispositivo.
- El kit original de trabajo `kit-v480-reproducible/`, APK, binarios y builds.
- Configuración real de Bitcoin, Tor, SSH, Wi-Fi o rutas personales.
- Direcciones onion, IPs numéricas del equipo, MAC, identificadores de tarjetas,
  usuarios de Termux, claves públicas SSH y huellas del dispositivo.
- Claves privadas, keystores, contraseñas, tokens, cookies RPC y estado privado Tor.
- Wallets, descriptores, direcciones Bitcoin, xpubs, cadena y bases de datos.
- Capturas, volcados de UI y otros archivos que puedan mostrar información local.

Los ejemplos públicos usan `localhost` o marcadores. El primer commit de
documentación ya publicado contiene únicamente loopback genérico en sus
ejemplos de red; el escáner admite ese caso en el historial antiguo. Los commits
con el manifiesto se comprueban sin permitir IPs numéricas, incluso loopback.
Los autores de commits deben usar el correo privado noreply de GitHub.

## Antes de subir cambios

Revisar el contenido de cada archivo antes de agregar su ruta al manifiesto y
a `.gitignore`. Trabajar sobre las copias públicas; no copiar de nuevo los
originales privados sin revisarlos.

```sh
python tools/check_publication.py
python tools/test_publication.py
git add .
python tools/check_publication.py --staged
git diff --cached --stat
```

Para instalar los controles locales en cada clon:

```sh
git config core.hooksPath tools/hooks
```

El hook de commit inspecciona todo el índice. El hook de push inspecciona todos
los archivos y metadatos de todos los commits alcanzables desde la referencia
que se va a subir, para detectar también datos borrados en un commit posterior.
Ambos necesitan Python 3. Los hooks se activan localmente y pueden omitirse;
no son una política impuesta por el servidor.

También se puede comprobar una rama ya confirmada:

```sh
python tools/check_publication.py --history HEAD
```

El control informa archivo, línea y categoría, sin imprimir los valores que
detecta. Rechaza archivos ajenos al manifiesto, binarios, enlaces simbólicos,
tipos no revisados y patrones comunes de datos sensibles. Es una ayuda a la
revisión manual: no reconoce todos los secretos posibles ni acredita que la
aplicación sea segura frente a cualquier vulnerabilidad.

Las identidades y credenciales se generan localmente. Un APK propio no debe
publicarse sin revisar también su contenido y los datos de su certificado de
firma. Las imágenes requieren revisión visual por separado.
