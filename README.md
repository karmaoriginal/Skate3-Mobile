# SKATE 3 MOBILE, DEV IN PROGRESS!

## Novedades recientes, explicadas fácil 🇲🇽

### ¿Qué cambió? 📦

Antes, para instalar el juego en tu teléfono **solo** podías usar un archivo
ISO (una copia exacta del disco del Xbox 360). Y los mods y los *drivers* (que
son como manuales nuevos para la tarjeta gráfica) pedían cosas separadas o
formatos muy clavados.

Ahora la app entiende archivos **comprimidos**: `.zip`, `.rar`, `.7z`, `.tar`
y `.tar.gz`. Tres flujos distintos se benefician:

| ¿Qué quieres importar? | ¿Dónde? | ¿Qué necesitas? |
| --- | --- | --- |
| 🎮 **El juego** | Botón **Select ISO or Archive** | Un ISO *o* un archivo comprimido con el juego ya extraído (los mismos archivos que un ISO, pero comprimidos en `.zip`, `.rar`, `.7z`, `.tar` o `.tar.gz`) |
| 🐧 **El mod de Seiyu** | Characters > Restore Seiyu > **Import archive** | Un solo archivo con `base.obj` (el modelo 3D) y `texture_diffuse.png` (su dibujo/pintura). Ya no hay que elegir dos archivos por separado |
| ⚙️ **Drivers de GPU** (experimentales, solo Snapdragon/Adreno) | Advanced Options > GPU driver experiments > **Import driver archive** | El paquete del driver, en `.zip`, `.rar`, `.7z`, `.tar`, `.tar.gz` o `.adpkg` |

¿Y por qué antes no se podía? Porque cada formato de compresión es como un
idioma distinto. `.zip` lo habla Android desde siempre, pero `.rar` y `.7z`
necesitan traductores especiales, y hasta ahora la app no traía ninguno.

### ¿Con qué se hizo? 🧰

| Pieza | ¿Para qué sirve? | ¿Por qué esta? |
| --- | --- | --- |
| `java.util.zip` (viene con el JDK de Android) | Descomprimir `.zip` | Ya está adentro, gratis, y no aumenta el tamaño de la app |
| **junrar 8.1.2** | Descomprimir `.rar` | El estándar libre de facto para leer RAR (licencia UnRar: se puede usar y distribuir gratis) |
| **Apache Commons Compress 1.27.1** | Descomprimir `.7z`, `.tar` y `.tar.gz` | La librería más madura y probada del mundo Java para esto (Apache-2.0, igual que muchos proyectos que ya usamos) |
| **ArchiveExtractor** (`android/.../ArchiveExtractor.java`) | El "guardia de seguridad" nuevo de la casa | Detecta el formato por las **huellas digitales** del archivo (sus primeros bytes), nunca por el nombre; nadie con un archivo trampa puede escaparse de su carpeta |

El guardia de seguridad revisa todo antes de dejar entrar archivos:

- **Rutas trampa**: un archivo malicioso puede traer rutas como `../../sistema/borrar.todo`. Aquí se rechazan, punto.
- **Duplicados**: si dos archivos piden el mismo nombre, la importación se detiene con un mensaje claro.
- **Enlaces extraños** (symlinks): se ignoran, no se crean.
- **Límites de tamaño**: cada flujo tiene sus topes (el juego puede pesar varios GB; un mod de Seiyu, 128 MB como máximo). Un archivo gigante o corrupto no llena tu teléfono.
- **Diccionarios acotados**: los `.rar`/`.7z` pueden pedir memorias absurdas para descomprimirse; aquí se limita a 256 MB, pensado para teléfonos de 4 GB como el Honor X5 Plus.
- **Sin contraseña**: si el archivo viene cifrado, la app te lo dice y no toca nada.

### ¿Por qué se hizo? 🤷

1. **Porque no todos tienen la ISO a la mano.** Mucha gente ya tiene el juego
   extraído en su computadora y quiere pasarlo al teléfono comprimido, que es
   como viajan esos archivos por internet.
2. **Porque instalar a Seiyu era un estorbo de dos pasos.** Primero el
   `base.obj`, luego la `texture_diffuse.png`… un solo archivo es más fácil y
   falla menos (y sigue valiendo el importador de dos archivos para los que
   ya lo conocen).
3. **Porque los drivers de la comunidad vienen en muchos formatos.** Amarrarnos a
   `.zip` dejaba fuera a mucha gente con `.rar` o `.7z` perfectamente válidos.

### Lo que se arregló de paso 🔧

- **La textura de Seiyu pesaba 2.1 MB; ahora pesa 574 KB** (una imagen de 1024
  px en vez de 2048 px). Se ve igual de bien a ojo normal y la app y el Mod
  Store cargan mucho más rápido.
- **El instalador de Seiyu incluido en la app tenía "los papeles vencidos"**:
  esperaba la textura vieja (por hash y por tamaño), así que nunca habría dado
  por buena la nueva. Ya está corregido; los teléfonos que la instalaron se
  re-verifican y se auto-reparan solos.
- **El ZIP manual del mod** (el que descargas del Mod Store) ya trae la
  textura nueva y las instrucciones actualizadas.

### Lo que había llegado justo antes en esta misma actualización 📱

- **Perfil Ultra Low para teléfonos de entrada** (tipo Honor X5 Plus, Helio G36
  con 4 GB de RAM): la app detecta el teléfono y baja a 216p y 30 FPS por su
  cuenta. Es una *meta*, no una promesa. En este ambiente no se pudo probar en un
  teléfono real todavía — ver
  [docs/LOW_END_DEVICES.md](docs/LOW_END_DEVICES.md).
- **Controles táctiles 100% personalizables**: presets (Normal, Compacto,
  Botones grandes, Espejo zurdo), tamaños S/M/L, opacidad, vibración y hasta
  arrastrar cada botón a tu gusto. Se abre con el engranaje ⚙ durante el juego.

### Lo honesto que hay que decir 🙏

- Esto todavía **no se ha compilado ni probado en un teléfono de verdad** desde
  este ambiente (hace falta la Mac del proyecto y un dump legal del juego).
  El primer build en Mac descargará ~1.5 MB de librerías nuevas de internet,
  que es normal.
- Formatos **no** soportados, para que no haya sorpresas: archivos con
  contraseña; `.7z` con filtro BCJ (re-guárdalos como `.zip`); `.rar`
  autoextraíbles (`.exe`); y `.tar.gz` del juego completo (duplica el espacio
  temporal en el teléfono — para el juego mejor `.zip`, `.7z` o `.rar`).
- Todos los detalles técnicos y bitácora de cambios están en
  [docs/RELEASE_NOTES_v2.2.0.md](docs/RELEASE_NOTES_v2.2.0.md) y en
  [android/README.md](android/README.md).
