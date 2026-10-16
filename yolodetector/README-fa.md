# افزودن حرکت نرم (Human-like) به YoloDetector-screen

## فایل‌ها
کپی کنید داخل پروژه (اگر package شما فرق دارد، خط `package` بالای فایل‌ها را عوض کنید):

- `gesture/HumanMotion.kt` — تولید مسیر: بزیه + minimum-jerk + فیتس + لرزش ریز + overshoot
- `gesture/SmoothGestureService.kt` — AccessibilityService و زنجیره‌ی `continueStroke`
- `gesture/AimController.kt` — تبدیل باکس‌های YOLO به حرکت دوربین
- `res/xml/smooth_gesture_service.xml`

## AndroidManifest.xml (داخل `<application>`)
```xml
<service
    android:name=".gesture.SmoothGestureService"
    android:exported="false"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data
        android:name="android.accessibilityservice"
        android:resource="@xml/smooth_gesture_service" />
</service>
```

## strings.xml
```xml
<string name="smooth_gesture_desc">اجرای حرکت‌های نرم برای اتوماسیون محلی</string>
```

`minSdk` باید حداقل ۲۶ باشد (`continueStroke`).

## خط لوله‌ی یکپارچه
```
ControlActivity ──(اجازه‌ی ضبط)──▶ ScreenDetectService (foreground)
                                     │ ImageReader روی HandlerThread جدا
                                     ▼
                                  FrameDetector.detect(bitmap)   ← YOLOv8 شما
                                     │ فیلتر label/score + تبدیل به پیکسل صفحه
                                     ▼
                                  AimController.onDetections()   ← EMA + dead-zone + rate-limit
                                     │ delta
                                     ▼
                                  SmoothGestureService.steerTowards()
                                     │ HumanMotion.plan → chunks
                                     ▼
                                  dispatchGesture + continueStroke
```

فایل‌های لایه‌ی اسکن:
- `screen/ScreenDetectService.kt` — MediaProjection، ImageReader، backpressure، تبدیل مختصات
- `screen/ControlActivity.kt` — دکمه‌های فعال‌سازی سرویس و شروع/توقف

### Detector: `screen/YoloTfliteDetector.kt`
پیاده‌سازی کامل `FrameDetector` برای YOLOv8 export‌شده با اولترالیتیکس:
letterbox ← ورودی float32/uint8/int8 ← اجرا (GPU با fallback به CPU) ← decode خروجی
`[1,84,8400]` یا `[1,8400,84]` ← NMS ← معکوس letterbox.

مسیر مختصات در کل خط لوله:
```
فضای مدل (640×640)
   └─ − padding ، ÷ scale         ← YoloTfliteDetector
مختصات بیت‌مپ ضبط (capW×capH)
   └─ × toScreenX/Y              ← ScreenDetectService
پیکسل واقعی صفحه
   └─ − مرکز صفحه ، × sensitivity ← AimController
delta کشیدن → SmoothGestureService.steerTowards
```

فایل‌های لازم در `app/src/main/assets/`:
- `yolov8n.tflite` (یا مدل خودتان؛ نام را در ControlActivity عوض کنید)
- `labels.txt` — یک نام کلاس در هر خط، به همان ترتیب آموزش

build.gradle (ماژول app):
```gradle
android {
    androidResources { noCompress += "tflite" }   // برای memory-map شدن مدل
}
dependencies {
    implementation "org.tensorflow:tensorflow-lite:2.16.1"
    implementation "org.tensorflow:tensorflow-lite-gpu:2.16.1"
    implementation "org.tensorflow:tensorflow-lite-gpu-api:2.16.1"
}
```

اگر Detector قبلی خودتان را نگه می‌دارید، فقط آداپتور بنویسید — شرطش این است که باکس‌ها را
**در مختصات بیت‌مپ ورودی** برگرداند (نه 640):
```kotlin
class YoloAdapter(private val inner: YourExistingDetector) : FrameDetector {
    override fun detect(frame: Bitmap) =
        inner.run(frame).map { Detection(it.rect, it.className, it.confidence) }
    override fun close() = inner.close()
}
```

**تست سریع درستی مختصات:** در Logcat تگ `YoloTflite` شکل ورودی/خروجی را چاپ می‌کند.
یک اسکرین‌شات از بازی را مستقیم به `detect()` بدهید و باکس‌ها را روی همان بیت‌مپ بکشید؛
اگر باکس‌ها جابه‌جا هستند معمولاً یعنی مدل با `imgsz` دیگری export شده یا normalized بودن خروجی اشتباه تشخیص داده شده.

### Manifest — موارد اضافه برای سرویس اسکن
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<service
    android:name=".screen.ScreenDetectService"
    android:exported="false"
    android:foregroundServiceType="mediaProjection" />

<activity android:name=".screen.ControlActivity" android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>
```

ترتیب اجرا: «فعال‌سازی سرویس حرکت» ← روشن کردن سرویس در تنظیمات ← «شروع اسکن» ← تأیید ضبط صفحه ← باز کردن بازی.

## تست ساده‌ی کشیدن
```kotlin
SmoothGestureService.instance?.swipe(PointF(300f, 1200f), PointF(900f, 1100f))
```

## تنظیم سرعت
| پارامتر | پیش‌فرض | اثر |
|---|---|---|
| `minDurationMs` / `maxDurationMs` | 110 / 520 | کف و سقف زمان هر حرکت |
| `framesPerChunk` | 3 | کمتر = نرم‌تر، بیشتر = dispatch کمتر |
| `curvature` | 0.10 | انحنای مسیر |
| `jitterPx` | 0.8 | لرزش ریز دست؛ ۰ = خاموش |
| `sensitivity` | 0.55 | کالیبره با حساسیت دوربین بازی |
| `minIntervalMs` | 90 | فاصله‌ی حداقلی بین دستورها |
| `smoothing` | 0.35 | صاف‌سازی لرزش باکس‌ها |

**کالیبره‌ی sensitivity:** یک swipe ثابت ۲۰۰ پیکسلی بزنید و ببینید هدف چند پیکسل جابجا می‌شود؛ `sensitivity ≈ 200 / جابجایی`. اگر دوربین از هدف رد می‌شود و برمی‌گردد (نوسان)، sensitivity را کم کنید.
