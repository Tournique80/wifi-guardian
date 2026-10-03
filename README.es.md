# Wi-Fi Guardián 🛡️📶

**¿El Wi-Fi de tu Pixel no enciende o se cae solo, y a veces arrastra al Bluetooth?**
Esta app gratuita y de código abierto no repara tu teléfono, pero puede devolverte el Wi-Fi. Aquí está el camino que recorrimos para llegar a ella.

*[Read in English](README.md)*

---

## El problema

En algunos Pixel (lo encontramos en un **Pixel 8 Pro**, probablemente también en otros con el mismo chip Wi-Fi/Bluetooth de Broadcom) el chip de Wi-Fi deja de responder al azar:

- El Wi-Fi aparece activado, pero el teléfono dice **"Wi-Fi desactivado"** y no encuentra redes.
- O funciona un rato y luego **se cae solo**, y no vuelve hasta reiniciar.
- A veces el Bluetooth se cae en bucle al mismo tiempo.
- Android incluso puede **revertir una y otra vez las actualizaciones del sistema de Google Play** y reiniciar, porque cree que esas actualizaciones rompieron el Bluetooth.

Reinstalar el sistema, restablecer la red y borrar datos de apps no lo solucionó.

## Lo que descubrimos

Revisamos bugreports y registros del kernel. Todas las caídas se ven igual:

```
Link is not up, try count: 10, linksts: DETECT QUIET
pcie link up fail
hang reason: PCIE_RC_LINK_UP_FAIL
```

El procesador se comunica con el chip de Wi-Fi por una **conexión PCIe**, y esa conexión no logra establecerse. Dos hallazgos clave:

1. **Falla cuando el chip despierta.** El driver duerme el chip tras apenas **0,5 s sin tráfico** (`DHD Idle state!! idletime: 5, wdtick: 100`), así que la conexión se corta y se vuelve a establecer **miles de veces por hora**. Una conexión defectuosa termina fallando en uno de esos despertares.
2. **El calor lo empeora mucho.** La app registró la temperatura de la batería en cada falla:

| Encendido / evento | Resultado | Temp. batería |
|---|---|---|
| 13:07 | ❌ El Wi-Fi no arrancó | 34,1 °C |
| 13:22 | ❌ Arrancó y se cayó | 31,8 °C |
| 13:41 | ❌ No arrancó | 30,8 °C |
| 11:32 (en uso) | ❌ Se cayó | ~33,8 °C |
| 11:57 | ✅ Funcionó | 28,0 °C |
| 13:57 | ✅ Funcionó | 25,1 °C |

Sobre ~30 °C falló y bajo ~28 °C funcionó. Eso apunta a una conexión física sensible al calor, por ejemplo una soldadura agrietada: un fallo de **hardware**. No hace falta que el teléfono se sienta caliente; 31 °C se siente "normal" en la mano.

## Qué hace la app

**🛡️ Protege activamente, no es solo un monitor.**
Envía un paquete minúsculo de 1 byte a tu router cada **0,2 segundos**, solo por Wi-Fi. El chip nunca pasa 0,5 s ocioso, así que el driver nunca lo duerme y la conexión frágil nunca tiene que volver a establecerse, que es justo donde falla. Es como dejar el auto en ralentí porque sabes que al motor de arranque le cuesta partir.

- **No** modifica el chip, el driver ni el sistema. **No necesita root.**
- El tráfico va solo a tu router local (o a `8.8.8.8` si no se conoce su dirección), nunca por datos móviles.
- Si no hay Wi-Fi conectado, no hace nada.

**📊 Vigila y avisa.**
- Detecta cuando se cae el chip, incluso al encender, y te avisa.
- Registra la temperatura y te dice **cuándo el teléfono ya se enfrió lo suficiente para reiniciar**.
- Arranca sola al encender el teléfono.

## Si tu Wi-Fi ya está caído

1. **Apaga el teléfono por completo** (no reiniciar).
2. **Déjalo enfriar 15–20 minutos**: sin funda, sin cargador, en un lugar fresco o frente a un ventilador.
   ⚠️ **Nunca lo pongas en el freezer ni en el refrigerador**: al sacarlo se condensa humedad dentro del teléfono y puede dañarlo.
3. **Enciéndelo, abre Wi-Fi Guardián y activa el Wi-Fi.**
4. Úsalo normalmente: la app mantiene despierto el chip y te avisa si se vuelve a caer.

Consejos: evita usarlo mucho mientras carga (la carga lo calienta) y quítale la funda si es gruesa.

## Instalación

1. Descarga `WifiGuardian-x.y.z.apk` desde **[Releases](../../releases)**.
2. Ábrelo en el teléfono y permite instalar apps desde ese origen cuando Android lo pida.
3. Abre la app, permite las notificaciones y toca **Permitir** en la tarjeta de segundo plano.

Requiere Android 14 o superior. Probada en un Pixel 8 Pro con Android 17.

### Ajustes

- **Proteger con la pantalla apagada**: sigue protegiendo con el teléfono en el bolsillo, a cambio de más consumo de batería. Desactivado por defecto.
- **Límites de temperatura**: por defecto 28 °C (seguro para reiniciar) y 30 °C (zona de riesgo), medidos en un Pixel 8 Pro. Ajústalos para tu teléfono.

### Opcional: diagnóstico detallado (ADB)

Si quieres que la app guarde también los registros internos de Wi-Fi de Android (útil para reportar fallas):

```
adb shell pm grant io.github.tournique80.wifiguardian android.permission.READ_LOGS
adb shell pm grant io.github.tournique80.wifiguardian android.permission.DUMP
```

Android pide confirmación ("permitir por esta vez") y quita estos permisos en cada reinicio. Los registros quedan en `/sdcard/Android/data/io.github.tournique80.wifiguardian/files/`.

## Límites, con honestidad

- Es una **mitigación para un problema de hardware, no una reparación**. Si la conexión está muy dañada, ningún software lo va a resolver.
- No puede evitar una falla **al encender** (la conexión tiene que establecerse al menos una vez); para eso sirven los pasos de enfriamiento.
- Mantener el chip despierto consume algo más de batería.
- Por ahora está probada en un solo teléfono. Comparte tus resultados en [Issues](../../issues): ayudan a todos.

## Compilar desde el código

No necesita Gradle. Con JDK 17 y el SDK de Android (build-tools 36, plataforma android-36):

```
JAVA_HOME=... BUILD_TOOLS=.../build-tools/36.0.0 ANDROID_JAR=.../platforms/android-36/android.jar ./build.sh
```

## Licencia

MIT: libre para usar, compartir y mejorar.

---

Hecho con ❤️ en Chile 🇨🇱 · by **Totihue**
