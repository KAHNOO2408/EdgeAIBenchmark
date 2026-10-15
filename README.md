# Edge AI Benchmark — راه‌اندازی

## ۱. قرار دادن مدل (ضروری)
دو فایل باید در `app/src/main/assets/` قرار بگیرند:

| فایل | توضیح |
|---|---|
| `model.tflite` | مدل طبقه‌بندی تصویر (هر مدل MobileNet با ورودی ۲۲۴×۲۲۴) |
| `labels.txt` | برچسب کلاس‌ها، هر خط یک برچسب (اختیاری؛ بدون آن «کلاس #n» نمایش داده می‌شود) |

پیشنهاد (هر دو فایل داخل یک zip رسمی TensorFlow):

- **کوانتیزه (UINT8)** — مناسب NNAPI/NPU:
  `https://storage.googleapis.com/download.tensorflow.org/models/tflite/mobilenet_v1_1.0_224_quant_and_labels.zip`
  محتویات zip: فایل `mobilenet_v1_1.0_224_quant.tflite` را به `model.tflite` و `labels_mobilenet_quant_v1_224.txt` را به `labels.txt` تغییر نام دهید.

- **شناور (FLOAT32)** — مناسب GPU:
  `https://storage.googleapis.com/download.tensorflow.org/models/tflite_11_05_08/mobilenet_v2_1.0_224.tgz`
  (برچسب‌ها همان فایل labels بالا با ۱۰۰۱ خط است.)

کد نوع مدل (float یا quant) و ابعاد ورودی را خودش از فایل مدل می‌خواند؛ لازم نیست چیزی را در کد عوض کنید.

## ۲. اجرا
1. پوشه را در Android Studio باز کنید (Gradle خودش همگام می‌شود).
2. روی **گوشی واقعی** اجرا کنید (شبیه‌ساز GPU/NNAPI واقعی ندارد).
3. از بالای صفحه CPU / GPU / NNAPI را انتخاب کنید.
4. دکمه‌ی «شروع بنچمارک» را بزنید: ۱۰ فریم گرم‌کردن حذف، ۳۰۰ فریم ثبت و دو فایل CSV ذخیره می‌شود.

## ۲.۱ حالت رقابت منابع (پنل شناور)
1. (اختیاری ولی بهتر) چند عکس jpg/png در `app/src/main/assets/test_images/` بگذارید. بدون آن، تصاویر مصنوعی استفاده می‌شود.
2. سخت‌افزار را انتخاب و «حالت رقابت منابع» را بزنید؛ مجوز اعلان و «نمایش روی برنامه‌های دیگر» را بدهید.
3. روی صفحه‌ی Home حدود ۶۰ ثانیه در مرحله‌ی «پایه» بمانید.
4. مرحله را «زیر بار» کنید، بازی را باز کنید و ۶۰ ثانیه بازی کنید.
5. «ذخیره CSV» → فایل `contention_<ACC>_<time>.csv` و خلاصه‌اش. دکمه‌ی ✕ سرویس را می‌بندد.

## ۳. گرفتن فایل‌های CSV
```
adb pull /sdcard/Android/data/com.academic.edgebenchmark/files/ ./results
```
- `benchmark_<ACC>_<time>.csv` — داده‌ی خام هر فریم
- `benchmark_<ACC>_<time>_summary.csv` — میانگین، میانه، p90، min، max، انحراف معیار + مشخصات گوشی

## ۴. ساخت نسخه‌ی تحویلی
`Build > Generate Signed App Bundle / APK > APK > release`

## ساخت خودکار در گیت‌هاب
هر push به شاخه‌ی `main` با GitHub Actions یک APK می‌سازد (مدل به‌طور خودکار دانلود می‌شود).
APK آماده در بخش **Releases → latest** قرار می‌گیرد.
