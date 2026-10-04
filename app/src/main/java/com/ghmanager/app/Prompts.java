package com.ghmanager.app;

import java.util.ArrayList;
import java.util.List;

/**
 * Ready-made prompts for working with an AI assistant on GitHub uploads (files and folders). The
 * placeholders {REPO}, {OWNER} and {BRANCH} are filled in with the repository the user is in.
 */
public final class Prompts {
    private Prompts() {
    }

    public static final class Item {
        public final String title;
        public final String text;
        public final int group;

        Item(int group, String title, String text) {
            this.group = group;
            this.title = title;
            this.text = text;
        }
    }

    public static final int G_FILES = 0;
    public static final int G_FOLDERS = 1;
    public static final int G_FIX = 2;
    public static final int G_RELEASE = 3;

    public static List<Item> all(boolean ar) {
        List<Item> l = new ArrayList<>();
        if (ar) {
            l.add(new Item(G_FILES, "رفع ملف واحد أو عدة ملفات",
                    "أريد رفع ملفات إلى مستودع GitHub باسم {REPO} (الفرع {BRANCH}).\n"
                            + "ملفاتي: [اذكر الأسماء أو الصق المحتوى]\n"
                            + "المطلوب:\n"
                            + "1) أخبرني بالمسار الصحيح لكل ملف داخل المستودع.\n"
                            + "2) أعطني كل ملف جاهزاً كاملاً (لا مقتطفات) مع اسمه ومساره.\n"
                            + "3) اقترح رسالة commit قصيرة بصيغة Conventional Commits.\n"
                            + "4) نبّهني إن كان هناك ملف قد يحوي أسراراً (توكن، كلمة سر، مفتاح) قبل الرفع."));
            l.add(new Item(G_FILES, "إرسال الملفات الجديدة والمعدّلة فقط",
                    "عدّل مشروعي في المستودع {REPO} وأرسل لي فقط الملفات الجديدة أو المعدّلة، "
                            + "بنفس مساراتها داخل المشروع، داخل ملف zip واحد.\n"
                            + "قبل الإرسال افحص الكود وأصلح أي خطأ تجده، ثم اذكر لي قائمة الملفات التي تغيّرت وسبب كل تغيير."));
            l.add(new Item(G_FILES, "فحص الملفات قبل الرفع (أسرار وأحجام)",
                    "سأرفع ملفات إلى {REPO}. افحص القائمة التالية قبل الرفع:\n[الصق أسماء الملفات وأحجامها]\n"
                            + "- أي ملف أكبر من 100MB (حد GitHub) وكيف أتعامل معه (Git LFS أو Release).\n"
                            + "- أي ملف قد يحوي توكن أو كلمة سر أو مفتاح خاص (.env, keystore, *.pem).\n"
                            + "- ملفات لا يجب رفعها (build, node_modules, .idea) وأعطني .gitignore مناسباً."));
            l.add(new Item(G_FOLDERS, "رفع مجلد كامل بهيكله",
                    "أريد رفع مجلد كامل إلى {REPO} (الفرع {BRANCH}) مع الحفاظ على هيكله.\n"
                            + "هذا شكل المجلد:\n[الصق الشجرة]\n"
                            + "المطلوب: رتّب الهيكل المناسب لمشروع من هذا النوع، واذكر ما يجب نقله أو إعادة تسميته، "
                            + "ثم أعطني الملفات النهائية في zip واحد بنفس المسارات."));
            l.add(new Item(G_FOLDERS, "تقسيم مجلد كبير إلى دفعات",
                    "مجلدي كبير ولا يُرفع دفعة واحدة إلى {REPO}. قسّمه إلى دفعات منطقية (كل دفعة أقل من 25MB وأقل من 100 ملف)، "
                            + "مع رسالة commit لكل دفعة وترتيب الرفع الصحيح حتى لا يتعطل المشروع بين الدفعات.\n"
                            + "قائمة المجلد: [الصق الشجرة والأحجام]"));
            l.add(new Item(G_FOLDERS, "هيكلة مستودع جديد من الصفر",
                    "أنشئ لي هيكل مستودع جديد باسم {REPO} لمشروع [نوع المشروع]، يتضمن: README.md، .gitignore، LICENSE، "
                            + "مجلدات المصدر والاختبارات، وملف workflow أساسي في .github/workflows. "
                            + "أعطني كل ملف كاملاً مع مساره."));
            l.add(new Item(G_FIX, "رفض الرفع: ملف أكبر من 100MB",
                    "GitHub رفض رفع ملف أكبر من 100MB إلى {REPO}. اشرح لي الحلول بالترتيب (Git LFS، Release asset، "
                            + "تقسيم الملف، ضغط) واختر الأنسب لملف من نوع [نوع الملف]، مع الخطوات من جوال أندرويد فقط."));
            l.add(new Item(G_FIX, "تعارض عند الرفع (conflict / non-fast-forward)",
                    "ظهر لي خطأ تعارض عند رفع تعديلاتي إلى الفرع {BRANCH} في {REPO}:\n[الصق نص الخطأ]\n"
                            + "اشرح سببه بلغة بسيطة، وأعطني أسلم طريقة لحله دون فقدان تعديلاتي، وأنا أعمل من تطبيق جوال بلا سطر أوامر."));
            l.add(new Item(G_FIX, "ملف أُرسل لي ناقصاً أو مقطوعاً",
                    "الملف الذي أرسلته ناقص أو مقطوع. أعد إرساله كاملاً من أول سطر إلى آخر سطر دون اختصار أو \"...\"، "
                            + "وتأكد من أنه يعمل قبل الإرسال."));
            l.add(new Item(G_RELEASE, "إعداد workflow يطلب رقم الـ Tag عند التشغيل",
                    "عدّل ملف workflow في {REPO} ليبني التطبيق وينشر Release عند التشغيل اليدوي، "
                            + "بحيث يعرّف workflow_dispatch مدخلاً باسم tag (نص، مثال v1.2.0) ومدخلاً build_type (release/debug) "
                            + "ومدخلاً publish_release (boolean). يجب أن ينشئ الـ Release بالـ tag الذي أدخلته ويرفع الملف الناتج إليه. "
                            + "أعطني ملف yml كاملاً."));
            l.add(new Item(G_RELEASE, "كتابة ملاحظات إصدار (Release notes)",
                    "اكتب ملاحظات إصدار لـ {REPO} بصيغة Markdown منظمة (ميزات جديدة، تحسينات، إصلاحات، ملاحظات التثبيت) "
                            + "اعتماداً على هذه التغييرات:\n[الصق سجل الـ commits]"));
        } else {
            l.add(new Item(G_FILES, "Upload one or several files",
                    "I want to upload files to the GitHub repository {REPO} (branch {BRANCH}).\n"
                            + "My files: [names or pasted content]\n"
                            + "Please: 1) tell me the right path of each file in the repo, 2) give every file complete "
                            + "(no snippets) with name and path, 3) suggest a short Conventional Commits message, "
                            + "4) warn me about anything that may contain secrets (tokens, passwords, keys) before I push."));
            l.add(new Item(G_FILES, "Send only new and changed files",
                    "Modify my project in {REPO} and send me only the new or changed files, with the same paths as in the "
                            + "project, inside one zip. Check the code and fix any error before sending, then list what changed and why."));
            l.add(new Item(G_FILES, "Pre-upload check (secrets and sizes)",
                    "I am about to upload files to {REPO}. Check this list first:\n[paste file names and sizes]\n"
                            + "- any file over 100MB (GitHub limit) and how to handle it (Git LFS or a Release),\n"
                            + "- any file that may hold a token, password or private key (.env, keystore, *.pem),\n"
                            + "- files that must not be uploaded (build, node_modules, .idea) and a matching .gitignore."));
            l.add(new Item(G_FOLDERS, "Upload a whole folder with its structure",
                    "I want to upload a whole folder to {REPO} (branch {BRANCH}) keeping its structure.\n"
                            + "Folder tree:\n[paste tree]\nOrganise the right layout for this kind of project, tell me what to move "
                            + "or rename, then give me the final files in one zip with the same paths."));
            l.add(new Item(G_FOLDERS, "Split a large folder into batches",
                    "My folder is too large to upload to {REPO} at once. Split it into logical batches (each under 25MB and "
                            + "100 files) with a commit message per batch and the right upload order so the project never breaks "
                            + "between batches.\nFolder listing: [paste tree and sizes]"));
            l.add(new Item(G_FOLDERS, "Structure a new repository from scratch",
                    "Create a new repository layout named {REPO} for a [project type] project: README.md, .gitignore, LICENSE, "
                            + "source and test folders and a basic workflow in .github/workflows. Give every file complete with its path."));
            l.add(new Item(G_FIX, "Push rejected: file over 100MB",
                    "GitHub rejected a file over 100MB in {REPO}. Explain the options in order (Git LFS, Release asset, "
                            + "splitting, compression) and pick the best for a [file type], with steps I can do from an Android phone only."));
            l.add(new Item(G_FIX, "Conflict on push (non-fast-forward)",
                    "I got a conflict pushing to branch {BRANCH} in {REPO}:\n[paste the error]\nExplain the cause simply and give "
                            + "me the safest way to resolve it without losing my changes. I work from a mobile app, no command line."));
            l.add(new Item(G_FIX, "A file came back incomplete",
                    "The file you sent is incomplete or cut off. Send it again in full, from the first line to the last, with no "
                            + "shortening or \"...\", and make sure it works before sending."));
            l.add(new Item(G_RELEASE, "Workflow that asks for the Tag when run",
                    "Edit the workflow in {REPO} so it builds the app and publishes a Release on manual run. workflow_dispatch "
                            + "must declare an input named tag (string, e.g. v1.2.0), build_type (release/debug) and publish_release "
                            + "(boolean). It must create the Release with the tag I enter and attach the built file. Give me the full yml."));
            l.add(new Item(G_RELEASE, "Write release notes",
                    "Write release notes for {REPO} in tidy Markdown (new features, improvements, fixes, install notes) from "
                            + "these changes:\n[paste the commit log]"));
        }
        return l;
    }

    public static String fill(String text, String owner, String repo, String branch) {
        String r = repo == null || repo.isEmpty() ? "[REPO]" : (owner == null || owner.isEmpty() ? repo : owner + "/" + repo);
        String b = branch == null || branch.isEmpty() ? "main" : branch;
        return text.replace("{REPO}", r).replace("{BRANCH}", b).replace("{OWNER}", owner == null ? "" : owner);
    }
}
