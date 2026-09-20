Rafat Accounting - Android

هذا المشروع يحوّل واجهة برنامج Rafat_Accounting_Samsung.py إلى تطبيق Android مستقل.
- لا يحتاج Termux بعد تثبيت التطبيق.
- البيانات الجديدة تحفظ محليًا داخل التطبيق باستخدام localStorage.
- لا يحتاج إنترنت.
- ملف accounting.db القديم من نسخة Python لا ينتقل تلقائيًا إلى التطبيق؛ البيانات القديمة تحتاج ترحيلًا منفصلًا إذا كانت موجودة.

لبناء APK:
1) افتح المشروع في Android Studio.
2) انتظر مزامنة Gradle.
3) Build > Build APK(s).
4) ثبّت ملف APK الناتج على Samsung.
