# GitHub Manager

Idiomas: [English](README.md) | [العربية](README.ar.md) | [Français](README.fr.md) | [Español](README.es.md) | [Türkçe](README.tr.md) | [Русский](README.ru.md)

Uso seguro y proteccion contra el mal uso: [SAFE_USE.md](SAFE_USE.md)

GitHub Manager es una aplicacion nativa de Android (Java) para gestionar desde el telefono repositorios de GitHub, archivos, flujos de trabajo de Actions, issues y releases.

## Funciones

Repositorios y archivos
- Listar, buscar y filtrar tus repositorios, y limpiar los antiguos.
- Subir carpetas completas desde el telefono conservando la estructura. La aplicacion omite los archivos identicos, respeta .gitignore, muestra una vista previa (nuevo, modificado, identico) antes de subir y reanuda tras un fallo.
- Descargar archivos, carpetas o repositorios completos con pausa, reanudacion y cancelacion. Las transferencias siguen en segundo plano con una notificacion.
- Administrador de archivos del dispositivo en lista y cuadricula, seleccion multiple arrastrando, busqueda y una papelera interna que se vacia sola a los 30 dias.
- Editor de codigo con resaltado de sintaxis, numeros de linea, deshacer y rehacer, busqueda en el codigo, vista de Markdown y diferencias por linea.

Ramas y commits
- Crear, fusionar y eliminar ramas, y definir la rama predeterminada.
- Ver el historial de commits y las diferencias de cada uno.

GitHub Actions
- Listar workflows y seguir las ejecuciones en vivo, con cada job, cada paso y un cronometro.
- Visor de registros con agrupacion por pasos, numeros de linea y filtro de solo errores.
- Iniciar un workflow manualmente con entradas y limpiar ejecuciones antiguas.
- Descargar artifacts, con extraccion automatica del APK.
- Compilar y publicar una release desde una sola pantalla.
- SEMO AI: una revision sin conexion antes de iniciar un workflow. Detecta parentesis o llaves sin cerrar y errores de formato seguros, y corrige solo lo que es seguro. No hace llamadas de red ni usa ningun modelo.

Issues, releases y colaboracion
- Issues: listar, filtrar, crear, comentar y editar.
- Releases: ver, publicar, eliminar y descargar sus archivos.
- Colaboradores: invitar personas y cambiar su nivel de permiso.
- Estadisticas del repositorio: lenguajes, trafico y contribuidores.
- Ajustes de seguridad: proteccion de ramas, escaneo de secretos, proteccion de push, actualizaciones de seguridad de Dependabot y permisos predeterminados de Actions.
- Secretos de Actions cifrados y configuracion de la clave de firma.

Seguridad y privacidad
- El token de acceso se guarda con el Android Keystore.
- Bloqueo opcional de la aplicacion con huella, patron o PIN del telefono.
- Las capturas de pantalla estan bloqueadas por defecto dentro de la aplicacion; se pueden permitir en Ajustes, Seguridad.
- Solo HTTPS, confiando unicamente en los certificados del sistema. Copias de seguridad desactivadas.
- El APK puede sellarse al compilar y la aplicacion comprueba el sello al iniciar.
- Las actualizaciones se verifican con SHA-256 y con el certificado de firma.

Otros
- Espejo opcional mediante un Cloudflare Worker para redes donde GitHub es lento o esta bloqueado.
- Interfaz en arabe e ingles, temas claro y oscuro, varias cuentas.

## Activacion

1. Instalar el APK
   - Descarga el APK desde la pagina Releases de este repositorio e instalalo. Permite "Instalar aplicaciones desconocidas" para la aplicacion con la que abriste el archivo.
   - Se requiere Android 7.0 (API 24) o superior.

2. Iniciar sesion (elige una)
   - Device flow: toca la opcion de Google o correo, GitHub muestra un codigo y lo introduces en la pagina oficial de GitHub. Tu contrasena nunca pasa por la aplicacion. Requiere una OAuth App de GitHub con Device Flow activado; su Client ID es publico por diseno. Ponlo en la variable del repositorio GH_OAUTH_CLIENT_ID (Settings, Secrets and variables, Actions, Variables) antes de compilar, o introducelo en la aplicacion con la opcion Client ID.
   - Token de acceso personal: crea un token en GitHub y pegalo en la aplicacion. Dale solo los permisos que necesites. El device flow solicita: repo, workflow, delete_repo, read:org, user, admin:public_key.

3. Proteccion opcional
   - Ajustes, Bloqueo de la aplicacion: activalo y elige el retraso. El telefono debe tener ya un bloqueo de pantalla.

## Compilar desde el codigo con GitHub Actions

1. Crea una clave de firma permanente. Ejecuta el workflow "Generate signing key" solo en una copia PRIVADA del repositorio; se niega a ejecutarse en un repositorio publico. Descarga el artifact, guarda los archivos sin conexion en un lugar seguro y elimina el artifact. Tambien puedes ejecutar tools/make-keystore.sh en tu ordenador.
2. Anade los secretos del repositorio: KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD.
3. Ejecuta el workflow "Build APK". La release aparece en la pagina Releases.
4. Nunca subas el keystore, contrasenas ni tokens al repositorio.

## Desplegar el Cloudflare Worker (opcional)

El Worker solo reenvia solicitudes a hosts de GitHub y rechaza toda solicitud que no lleve tu clave secreta.

1. Instala Node.js y abre una terminal en la carpeta worker.
2. Inicia sesion: npx wrangler login
3. Despliega: npx wrangler deploy
4. Crea una clave aleatoria larga: openssl rand -hex 32
5. Guardala como secreto: npx wrangler secret put MIRROR_KEY y pega la clave. Sin este secreto el Worker responde 503 a todo.
6. En la aplicacion abre Ajustes, Espejo (Mirror). Introduce la direccion del Worker y la clave y pulsa Probar.
7. Recomendado: en Cloudflare desactiva la direccion workers.dev, usa tu propio dominio y anade una regla de limite de solicitudes. Activa la verificacion en dos pasos en tu cuenta de Cloudflare.
8. Si la clave se expone, cambiala de inmediato con el mismo comando.

## Cumplir las reglas de GitHub

Lee SAFE_USE.md para la politica completa de proteccion y mal uso.
