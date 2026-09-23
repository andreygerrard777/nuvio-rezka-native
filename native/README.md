# Rezka: нативний діагностичний прототип для Nuvio

Це вихідний проєкт CloudStream-сумісного `.cs3`, який працюватиме на Android TV.
Сервер для виконання не потрібен. Пошуку фільмів, розв'язання Anubis і відтворення
у цій першій версії ще немає. Мета — перевірити сумісність нативного розширення
та HTTP-клієнта перед перенесенням повного Rezka.

## Стан

Підготовлені Kotlin-код, Gradle-wrapper, JVM-тести cookie/redirect та workflow.
На комп'ютері розробки Java/Android SDK не знайдені; збірка й JVM-тести ще не
виконані. Готового перевіреного `.cs3` і публічного URL встановлення поки немає.
CloudStream API `pre-release` і Gradle-плагін `-SNAPSHOT` взяті зі зразка: їхню
сумісність треба перевірити першою збіркою, після успіху зафіксувати версії.
Локально пройшли 3 тести Python-пакувальника; вони перевіряють лише формування
JSON/пакета на синтетичному ZIP і не підтверджують компіляцію чи запуск `.cs3`.

## Такий самий формат встановлення, як у CakesTwix

`repo.json` → `plugins.json` → `RezkaDiagnostics.cs3`.

Перевірене посилання Codeberg:
https://codeberg.org/CakesTwix/cloudstream-extensions-uk/raw/branch/master/repo.json
Воно фактично посилається на GitHub builds/plugins.json. Це підтверджує, що місце
зберігання маніфесту і бінарних файлів може відрізнятися.

Наші JSON генеруються лише після збірки, із URL власного репозиторію та
контрольною сумою реального `.cs3`. Чужий repo.json змінювати не потрібно.
Наявність CakesTwix у каталозі Nuvio не означає автоматичного додавання туди
нашого нового плагіна. Для ручного встановлення каталог/короткий код не потрібні.

## Збірка в GitHub

1. Додати `native/` і `.github/workflows/native-rezka-prototype.yml` у власний
   GitHub-репозиторій. Поточні `manifest.json` та `providers/*.js` не змінюються.
2. Actions → **Build native Rezka diagnostics** → **Run workflow**.
3. Якщо збірка успішна, завантажити artifact `native-probe-N`. Він містить три
   файли: `.cs3`, `plugins.json`, `repo.json`. У разі збою потрібен лог збірки.
4. Створити GitHub release з тегом `native-probe-N` (N = номер запуску) та
   прикріпити ці три файли. Workflow сам нічого не публікує.
5. Додати в Nuvio адресу прикріпленого `repo.json` із release. Конкретна адреса
   друкується на кроці Assemble repository bundle. До публікації вона не працює.

Не використовуйте сторінку GitHub Actions artifact як URL встановлення: Nuvio
потрібні прямі доступні посилання на JSON і `.cs3`.

Локально потрібні JDK 17 та Android SDK 35, `ANDROID_HOME` або `local.properties`.
Із каталогу `native`: `./gradlew :RezkaDiagnostics:testDebugUnitTest :RezkaDiagnostics:make`
(на Windows — `./gradlew.bat`). Тести не звертаються до Rezka: використовують
локальний MockWebServer та перевіряють cookie на 302, scope/expiry і Anubis на 200.

## Перевірка на телевізорі

Потрібна full-збірка Nuvio з нативними плагінами. Після додавання репозиторію
мають з'явитися два провайдери:

1. **Rezka 1 - Runtime test**. Запустити тест провайдера. У звіті має бути
   `search() THREW: IllegalStateException: REZKA_RUNTIME_OK v1 ...`.
   Це навмисний діагностичний результат: Nuvio показує текст винятку у звіті.
2. **Rezka 2 - HTTP test**. Виконує один GET головної сторінки rezka.ag власним
   OkHttpClient. Результат: `REZKA_HTTP v1 status=... anubis=... setCookie=...`
   або `REZKA_HTTP_ERROR v1 ...`. Cookie та редиректи контролює клієнт плагіна.

**0 streams очікується в обох тестах.** Це не готовий постачальник відео.
Надішліть текст цих маркерів або знімок звіту. Якщо їх немає, потрібен увесь
діагностичний звіт, щоб розрізнити збій завантаження класів і збій TMDB до search.
Nuvio може написати `HTTP: no requests made by search()`: його перехоплювач
стежить за спільним CloudStream-клієнтом; наш клієнт окремий, орієнтуйтеся на
`REZKA_HTTP`. `anubis=false` саме по собі ще не доводить доступ до каталогу.

Після тесту вимкнути діагностичні провайдери, щоб вони не запускалися під час
звичайного пошуку. Наступний етап — cookie/challenge, потім повний пошук/потоки.

## Походження

Структура збірки, version catalog та Gradle-wrapper адаптовані з
CakesTwix/cloudstream-extensions-uk, commit
`dea43efe3746545f515344b1985ab8fb4dc8d36c`. Ліцензія GPL-3.0 у `LICENSE`.
Wrapper JAR SHA-256: `381DFF8AA434499AA93BC25572B049C8C586A67FAFF2C02F375E4F23E17E49DE`.
Новий код діагностики в цьому каталозі також надається під GPL-3.0.
