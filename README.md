# YOLO Detector — YOLOv8n + TFLite (Interpreter خام + GPU Delegate)

اپ اندرویدی کاتلین که مدل YOLOv8n را با `org.tensorflow.lite.Interpreter` اجرا می‌کند، اشیاء را در
صفحه‌ی گوشی (به‌صورت زنده با MediaProjection) یا عکس گالری تشخیص می‌دهد، کادر و برچسب می‌کشد و FPS و زمان‌های پیش‌پردازش،
استنتاج و پس‌پردازش را روی صفحه نشان می‌دهد.

## ساختار

```
YoloDetector/
├─ settings.gradle.kts · build.gradle.kts · gradle.properties
├─ gradle/wrapper/gradle-wrapper.properties
├─ .github/workflows/build.yml          ← بیلد APK در GitHub Actions
└─ app/
   ├─ build.gradle.kts
   └─ src/main/
      ├─ AndroidManifest.xml
      ├─ assets/
      │  ├─ labels.txt                    ← ۸۰ کلاس COCO
      │  └─ yolov8n.tflite                ← در ریپو هست (LiteRT، ورودی NCHW)
      ├─ res/layout/activity_main.xml
      └─ java/com/aspar/yolodetector/
         ├─ MainActivity.kt              ← مجوزها، شروع/توقف اسکن، گالری، نمایش آمار
         ├─ capture/ScreenCaptureService.kt ← MediaProjection + VirtualDisplay + ImageReader + مدل
         ├─ capture/ScreenOverlay.kt     ← پنجره‌ی شناور تمام‌صفحه که OverlayView را نگه می‌دارد
         ├─ capture/ScanState.kt         ← ScanSettings (تنظیمات مشترک) و ScanBus (سرویس ← Activity)
         ├─ ml/YoloDetector.kt           ← Interpreter + GpuDelegate، letterbox، decode، NMS
         ├─ ml/Detection.kt
         ├─ ui/OverlayView.kt            ← رسم Bounding Box
         └─ util/FpsCounter.kt · util/ImageUtils.kt
```

## ساخت فایل مدل

```bash
pip install ultralytics
yolo export model=yolov8n.pt format=tflite imgsz=640
cp yolov8n.tflite app/src/main/assets/   # نسخه‌های جدید ultralytics: pip install litert-torch
```

- برای سرعت بیشتر می‌توانید `imgsz=320` بدهید؛ اندازه‌ی ورودی از خود مدل خوانده می‌شود.
- نسخه‌ی `float32` هم کار می‌کند؛ فقط `MODEL_FILE` را در `MainActivity` عوض کنید.
- مدل‌های کوانتیزه‌ی int8/uint8 هم پشتیبانی می‌شوند، ولی GPU Delegate با float16 بهترین نتیجه را می‌دهد.
- اگر مدل در assets نباشد، workflow گیت‌هاب خودش آن را export می‌کند.

## نکات فنی

- **رشته‌ی ثابت:** GPU Delegate باید روی همان رشته‌ای اجرا شود که ساخته شده؛ ساخت مدل، آنالیز فریم‌ها و
  بنچمارک عکس همه روی یک `SingleThreadExecutor` انجام می‌شوند.
- **Fallback:** اگر GPU پشتیبانی نشود یا ساخت delegate خطا دهد، خودکار روی CPU با XNNPACK (۴ رشته) می‌رود
  و نام شتاب‌دهنده‌ی واقعی روی صفحه نمایش داده می‌شود.
- **Warm-up:** دو اجرای اولیه قبل از اندازه‌گیری، چون اولین اجرای GPU شامل کامپایل شیدرهاست.
- **خروجی:** هر دو چیدمان `[1,84,8400]` و `[1,8400,84]`، و مختصات نرمال یا پیکسلی خودکار تشخیص داده می‌شوند.
- **FPS دوربین:** فریم‌های پردازش‌شده در ثانیه (سرتاسری، با میانگین متحرک).
  **FPS عکس:** ۲۰ اجرای پشت‌سرهم پس از یک اجرای گرم‌کننده؛ زمان‌ها میانگین آن ۲۰ اجراست.
- سوئیچ «فقط وسایل نقلیه» کلاس‌های bicycle, car, motorcycle, bus, train, truck را نگه می‌دارد
  (`CocoClasses.VEHICLES`).

## اسکن زنده‌ی صفحه (جایگزین دوربین)

```
MediaProjection ─► VirtualDisplay (ضلع بلند 960) ─► ImageReader RGBA_8888
      │                                                  │ acquireLatestImage (فقط آخرین فریم)
      │                                                  ▼
 Foreground Service (mediaProjection)        Bitmap (حذف rowStride padding)
                                                         │ YoloDetector.detect  ← mlThread ثابت
                                                         ▼
                              ScreenOverlay (TYPE_APPLICATION_OVERLAY) ─► OverlayView.FitMode.SCREEN
                              ScanBus ─► MainActivity (آمار)
```

- ترتیب مجوزها: «نمایش روی برنامه‌های دیگر» ← اعلان (اندروید ۱۳+) ← دیالوگ ضبط صفحه.
- اندروید ۱۴+: `startForeground(type=MEDIA_PROJECTION)` قبل از `getMediaProjection`، ثبت
  `MediaProjection.Callback` قبل از `createVirtualDisplay`، و اجبار «کل صفحه» با
  `MediaProjectionConfig.createConfigForDefaultDisplay()` تا مختصات کادرها با صفحه یکی باشد.
- چرخش صفحه: در `onConfigurationChanged` سرویس، `VirtualDisplay.resize` و ImageReader جدید.
- پنجره‌ی Overlay لمس را رد می‌کند و alpha آن روی `maximumObscuringOpacityForTouch` است؛
  در غیر این صورت اندروید ۱۲+ لمس برنامه‌ی زیرین را مسدود می‌کند.
- کادرهای خود Overlay هم در ضبط صفحه دیده می‌شوند (API عمومی برای حذف پنجره‌ی خود برنامه از
  MediaProjection وجود ندارد)؛ چون خطوط نازک‌اند عملاً مشکلی ایجاد نمی‌کنند.
- VirtualDisplay فقط وقتی محتوای صفحه عوض شود فریم می‌دهد؛ صفحه‌ی ثابت = FPS نزدیک صفر.

## اتوماسیون لمسی (Gesture & Drag)

```
YoloDetector.detect ─► AimController.onFrame (mlThread)
   TargetMath.selectTarget   ← کلاس/امتیاز، CENTER یا HEAD، تبدیل فریم→پیکسل صفحه، قفل روی هدف قبلی
   EMA smoothing → dead zone → GestureThrottle (interval + یک ژست در جریان + watchdog)
   گام = gain × (هدف − crosshair)، محدود به maxStepPx
          └─► Main: GestureAccessibilityService.swipe(origin → origin+گام) با dispatchGesture
```

- فایل‌ها: `automation/GestureAccessibilityService.kt`، `TargetMath.kt`، `GestureThrottle.kt`، `AimController.kt`
- همه‌ی پارامترها در `AimConfig` است (پیش‌فرض: کلاس person، نقطه‌ی HEAD با headRatio=0.12،
  gain=0.6، deadZone=24px، maxStep=350px، ژست 60ms، فاصله‌ی حداقل 120ms).
- فعال‌سازی: سوئیچ «حرکت خودکار» ← روشن کردن سرویس در «تنظیمات > دسترس‌پذیری».
  روی اندروید ۱۳+ برای APK نصب‌شده خارج از Play: «اطلاعات برنامه ← ⋮ ← Allow restricted settings».
- روی Overlay، crosshair و نقطه‌ی هدف قرمز رسم می‌شوند و HUD تعداد ژست‌های ارسالی/ردشده را نشان می‌دهد.

### حرکت نرم شبیه دست (Human-like)
با `AimConfig.humanLike = true` (پیش‌فرض) به‌جای swipe خطی کوتاه، انگشت مجازی روی صفحه می‌ماند و پیوسته هدایت می‌شود:

- **مسیر:** منحنی بزیه‌ی مکعبی با انحنای تصادفی کم + لرزش ریز ~۱۰ هرتز که در ابتدا و انتها صفر می‌شود (`HumanMotion.kt`)
- **سرعت:** پروفایل minimum-jerk؛ مسیر به فریم‌های ۱۶ms با فاصله‌ی مکانی متغیر خرد و با `continueStroke` زنجیر می‌شود
- **زمان:** قانون فیتس، بین ۱۱۰ تا ۵۲۰ میلی‌ثانیه؛ گاهی overshoot کوچک در حرکت‌های بلند
- **تداوم:** هدف تازه وسط حرکت، بعد از تکه‌ی جاری از جای فعلی انگشت ادامه می‌یابد (بدون برداشتن انگشت)
- **لبه‌ی صفحه:** اگر انگشت به `edgeMarginPx` برسد، نرم برداشته و از `origin` دوباره گرفته می‌شود
- **گم شدن هدف:** بعد از `lostTargetResetMs` انگشت برداشته می‌شود

پارامترهای مسیر از `GestureAccessibilityService.instance?.motionConfig` قابل تنظیم‌اند (`curvature`، `jitterPx`، `minDurationMs`/`maxDurationMs`، `framesPerChunk`). با `humanLike = false` رفتار خطی قبلی برمی‌گردد.
