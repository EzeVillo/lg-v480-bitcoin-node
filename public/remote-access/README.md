# Túnel RPC por SSH en la LAN

El lanzador no tiene un host, usuario ni huella SSH predeterminados. Requiere
OpenSSH para Windows y un servidor SSH de Termux propio en el puerto 8022.

```powershell
.\Start-V480Tunnel.ps1 -TabletAddress TABLET_HOST -TermuxUser TERMUX_USER
```

Reemplazar los marcadores solo en la terminal. En la primera conexión,
comparar la huella presentada con la de la propia tablet, consultada por un
medio local confiable. La herramienta pregunta antes de guardar una clave
nueva y rechaza cambios de una clave ya conocida.

Con la ventana abierta, Sparrow puede usar Bitcoin Core en `localhost`,
puerto 8332. Si ese puerto está ocupado, agregar `-LocalPort` con otro puerto
libre y usarlo también en Sparrow. Las credenciales RPC son propias de esa
instalación y distintas de las de SSH. No se pasan en el script.

RPC y HTTP deben seguir escuchando solo en loopback en la tablet. Este script
no crea reenvíos remotos ni configura servicios onion administrativos.
