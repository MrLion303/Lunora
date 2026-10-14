# Lunora

Reproductor Android de música local, diseñado para escuchar la biblioteca de audio del dispositivo sin catálogo en línea ni subir canciones a servidores.

## Tecnologías
- Kotlin, Jetpack Compose y Material 3
- Android 8.0+ (API 26), JDK 17
- MediaStore para descubrir audio local
- Media3 ExoPlayer y MediaSessionService para reproducción en segundo plano
- GitHub Actions para generar el APK de depuración

## Funciones
Biblioteca local, búsqueda, favoritos, creación y edición de playlists, portadas elegidas, reproducción, cola, aleatorio, repetición, temporizador, tema claro/oscuro/sistema, controles de volumen y almacenamiento.

## Identidad visual
Acentos morados, un resplandor morado ambiental muy tenue y estático, Quicksand como tipografía principal y Zen Maru Gothic para dispositivos configurados en japonés.

## Compilación
Cada push a `main` ejecuta el workflow Android APK. Abre [Actions](https://github.com/MrLion303/Lunora/actions), entra en la ejecución y descarga el artefacto `Lunora-debug-apk`. Es un APK de pruebas; Google Play requiere una versión de publicación firmada y validación adicional.

## Estado de audio avanzado
Los controles iniciales de crossfade y audio mono están identificados como preferencias en desarrollo; la mezcla cruzada y el procesamiento mono requieren integración adicional en el motor de audio. El volumen es un control de salida de la aplicación, no un cambio del volumen maestro del dispositivo.
