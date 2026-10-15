# Kit público de LG V480

Esta carpeta contiene una copia revisada de las fuentes 1.16. Los originales
de trabajo, registros del dispositivo y binarios anteriores quedan fuera de Git.

| Carpeta | Contenido |
| --- | --- |
| [android](android/README.md) | App Java, recursos, manifiesto y compilación en Windows |
| [node](node/README.md) | API CGI, control de carga, ajustes reversibles y plantillas |
| [remote-access](remote-access/README.md) | Túnel SSH para RPC con parámetros propios |

Las rutas específicas se reemplazaron por configuración local. La API lee la
dirección onion del archivo `hostname` de Tor en tiempo de ejecución y deja ese
campo vacío si no existe; no contiene una dirección de respaldo incrustada.
La app usa `localhost` para consultar la API en la misma tablet.

Esta publicación no incluye un instalador completo de Termux/runit: los scripts
históricos de instalación y supervisión no están conservados en el kit actual.
Las plantillas requieren adaptación y verificación local antes de usarse.
La documentación del [proyecto](../README.md) distingue las pruebas históricas
de la preparación de estas fuentes para publicación.

Antes de publicar otra revisión, seguir [SECURITY.md](../SECURITY.md). No copiar
archivos de los respaldos a esta carpeta sin revisar su contenido.
