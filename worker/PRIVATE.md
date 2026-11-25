# جعل الـ Worker خاصاً

1. **المفتاح إجباري.** Cloudflare → Workers → اسم الـ Worker → Settings → Variables and Secrets → Add → النوع **Secret** → الاسم `MIRROR_KEY` والقيمة نص عشوائي طويل (32 حرفاً أو أكثر) → Deploy.
   أو من الطرفية: `npx wrangler secret put MIRROR_KEY`.
   بدون هذا السرّ يرفض الـ Worker كل الطلبات (503).
2. **ضع نفس المفتاح في التطبيق:** الإعدادات ← المرآة ← خانة "مفتاح الوصول" ← اضغط اختبار.
3. **المتصفح والكونسول ممنوعان:** الـ Worker لا يرسل CORS ويرفض أي طلب فيه `Origin` أو `Sec-Fetch-*`. لا تضف المتغير `ALLOW_BROWSER`.
4. **اخفِ عنوان workers.dev (اختياري):** Settings → Domains & Routes → عطّل workers.dev واستخدم نطاقك الخاص.
5. **حدّ الطلبات (اختياري):** Security → WAF → Rate limiting rules على نفس النطاق.
6. **حماية حسابك:** فعّل 2FA في Cloudflare، ولا تشارك مفتاح API، ولا تعطِ أحداً صلاحية Workers Edit.
   من يملك حسابك يستطيع تعديل الـ Worker من لوحة التحكم، ولا يوجد كود داخل الـ Worker يمنع ذلك.
7. غيّر `MIRROR_KEY` فوراً إذا شككت أن أحداً رآه.
