# Каталог и восстановление чтения «Кадра»

Статус на 20 ноября 2026 года — исследование и предложение контракта. Реализация начинается после сообщения «План утверждаю».

## 1. Исследованная версия и состояние

Изучены MangaSource, MangaSourceRegistry, SourceConnectionService, CatalogController, SourceModels, четыре адаптера, HTTP-клиенты, TitleRepository, обработка глав/изображений и Angular CatalogFacts. Учтены отчёты в backend/docs и frontend/docs/catalog-and-offline-plan.md.

Состав источников подтверждён GET /api/v1/sources, GET /api/v1/extensions и SELECT connected_sources.

| Источник | Подключён сейчас | Строк в titles | Текущий каталог приложения |
| --- | --- | --- | --- |
| MangaDex | Да | 302 | Первая порция из 24 результатов |
| MangaKakalot | Да | 24 | 502 SOURCE_BAD_RESPONSE |
| Pepper&Carrot | Да | 1 | Единственный тайтл |
| MangaLib | Нет | 228 | 409 SOURCE_DISABLED |

Прямые ссылки на карточки и главы отключённых источников работают по существующему контракту. MangaLib дополнительно проверен через такую ссылку. Состав участников первоначальной синхронизации необходимо получить снова перед её запуском.

В PostgreSQL применены V1–V9. Дублей (source_id, external_title_id) не найдено. Состояния обхода и подтверждения глав в БД отсутствуют. Сохранённые тайтлы сами по себе не доказывают полноту их сбора.

Для исследования запущены существующие PostgreSQL с прежним томом и JAR приложения на 8080. Новых миграций при запуске не выполнялось. GET каталога/карточек используют уже существующий upsert метаданных. Сырые диагностические ответы находятся в игнорируемом backend/target/catalog-research. Этот документ описывает следующие изменения, требующие утверждения.

## 2. Воспроизведение и причины

### Каталог

- MangaSource.searchTitles возвращает List без cursor/total/hasMore. CatalogController каждый раз опрашивает внешние сайты, затем синхронизирует полученные записи. По всей накопленной БД поиск не выполняется.
- MangaDex использует limit=24 без offset. Текущий запрос с RU/EN и hasAvailableChapters=true сообщает total=50 021. Запрос следующей порции с offset=24 также возвращает 24 строки. При перечислении safe/suggestive без ограничения по переводам и наличию глав source total=86 374. Эти значения относятся к разным условиям поиска.
- MangaKakalot обращается только к hot-manga?page=1. Текущий сайт поддерживает страницы. Страницы 1/2 содержат по 24 тайтла, пагинация указывает 4007, страница 4007 содержит 10 карточек, 4008 возвращает 404. Арифметический объём выдачи — 96 154 позиции. Уникальность всего корпуса и обход промежуточных страниц ещё предстоит подтвердить.
- MangaLib без q показывает SEO-подборку из 48 записей. Поиск использует только первую JSON-порцию. JSON API поддерживает page, meta.current_page, meta.per_page, meta.has_next_page и meta.seed. Проверенные страницы 1/2 содержат по 60 строк с has_next_page=true. Точное общее количество в ответе отсутствует.
- У Pepper&Carrot один тайтл, episodes.json перечисляет 39 выпусков целиком.
- Angular CatalogFacts отдельно вызывает /chapter-count для каждой видимой карточки. После истечения краткого кэша это повторно запрашивает внешний список глав.
- Общий каталог подавляет 5xx одного источника, если другой ответил успешно, но не сообщает клиенту, каких данных не хватает.

### Главы

| Тайтл и источник | Результат приложения | Проверенная причина |
| --- | --- | --- |
| Solo Leveling, MangaDex, 32d76d19-8a05-4db0-9fc2-e0b0648fe9d0 | /chapters → [], /chapter-count → 0 | Feed RU/EN содержит 24 externalUrl-выпуска с pages=0. Прямых страниц для нашего адаптера нет. |
| My Dress-Up Darling, MangaDex, aa6c76f7-5f5f-46b6-a800-911145f81b9b | Две русские главы, 115 и 115.5 | Переводы существуют, их количество и языки отличаются от языка названия. |
| The Eminence in Shadow, MangaDex, 77bee52c-d2d6-44ad-a33a-1734c1fe696a | 142 выпуска | Рабочий пример с английскими главами. |
| One Piece и Martial Peak, MangaKakalot | 502 | Старый домен/разметка и HTML при ожидании JSON. |
| Pepper&Carrot | 39 глав с RU/EN | Индекс получен полностью. |
| Человек-бензопила, MangaLib | 107 выбранных переводов, pageCount=0 | Ноль страниц установлен конструктором адаптера и означает отсутствие сведений. |

Для Solo Leveling успешный запрос feed с includeExternalUrl=0 и includeEmptyPages=0 вернул total=0. Это подтверждает отсутствие прямых встроенных RU/EN-глав в этой области доступности. Поисковый hasAvailableChapters=true такого свойства не гарантирует.

В коде обнаружены следующие потери достоверности:

1. MangaDex.loadFeed останавливается на 10 000 строках и возвращает обычный List. Достижение лимита не обозначается как PARTIAL. Отсутствующий total через asInt() превращается в 0 и может преждевременно остановить обход.
2. MangaDex отбрасывает внешние ссылки, нулевые страницы, неподдерживаемые языки и исключённые группы. Причины исключений теряются. chapterCount считает логические главы, а /chapters возвращает выпуски переводчиков.
3. MangaLib молча пропускает нечисловые volume/number и невалидные ID. Выбирает только первую доступную ветку. Если все строки пропущены, возвращается пустой список без признака неполноты.
4. MangaKakalot молча отбрасывает главы без корректных slug/name и задаёт pageCount=0. Полнота JSON с limit=-1 не проверяется отдельным контрактом.
5. Pepper&Carrot ограничивает переводы RU/EN. Это политика адаптера, её нужно явно отличать от отсутствия выпусков вообще.
6. Сетевые исключения в основном корректно доходят до 502/503. Основные причины ложных нулей — молчаливые пропуски, лимиты и отсутствие состояния проверки.

### Манифесты и изображения

MangaKakalot теперь перенаправляет kakamanga.com на katamanga.com через HTTP 301. Новый HTML использует Next.js и относительные ссылки. Наш PublicSourceClient не обрабатывает перенаправления, адаптер ожидает прежнюю разметку и домен. В журнале воспроизведены Missing popular manga cards и попытка разобрать HTML как JSON.

На новом домене One Piece имеет JSON с 1385 главами, Martial Peak — 3877. HTML первой главы One Piece содержит 54 адреса. Изображения 0, 27 и 53 вернули HTTP 403 и text/html с сообщением Cloudflare о блокировке. Исправление парсера и доступ к CDN являются отдельными проверками.

MangaLib успешно отдаёт манифест первой главы с 55 страницами через api2.mangalib.me. Сейчас secondary указывает img3.cdnrulib.link. Этот домен отсутствует в IMAGE_DOMAINS, поэтому приложение возвращает No image server for MangaLib. Прямой GET первого JPEG с secondary и compress вернул 200. Уже реализованная нормализация //manga/… корректна и должна сохраниться.

MangaDex уже имеет серверный /images/{index}, одно обновление манифеста после отказа и отчёт MangaDex@Home. Однако разные ошибки картинки сводятся к null и SOURCE_UNAVAILABLE.

Для прямого внешнего img Angular получает только событие error. Добавление _retry к URL не переполучает манифест и может нарушить подписанную ссылку.

| Фактическая HTTP-проверка | Страниц | Индексы | Ответы |
| --- | --- | --- | --- |
| Pepper&Carrot ep01 RU | 5 | 0, 2, 4 | 200 JPEG, сигнатура JPEG |
| Pepper&Carrot ep02 EN | 7 | 0, 3, 6 | 200 JPEG, сигнатура JPEG |
| My Dress-Up Darling 115 RU | 20 | 0, 10, 19 | 200 JPEG через Spring Boot |
| My Dress-Up Darling 115.5 RU | 26 | 0, 13, 25 | 200 JPEG через Spring Boot |
| One Piece 1 EN, новый сайт | 54 | 0, 27, 53 | 403 text/html |
| Человек-бензопила 1 RU | 55 upstream | Первая на двух CDN | 200 JPEG напрямую, 502 в приложении на разрешении хоста |

Это пробы фактических HTTP-тел. Проверка отображения исправлений в браузере входит в приёмку реализации.

## 3. Область полного сбора

Перечислять все доступные метаданные подключённого источника в действующей политике контента. Для MangaDex сохраняются safe/suggestive. Перечисление не зависит от hasAvailableChapters, иначе исчезнувшие/появившиеся главы невозможно учесть.

Языки чтения остаются RU/EN для MangaDex и Pepper&Carrot, EN для MangaKakalot, RU для MangaLib. Язык названия хранится отдельно. Полнота метаданных, полнота проверки глав и успешные пробы изображений считаются независимо.

У каждого обхода stopReason — EXHAUSTED, SOURCE_LIMIT, ACCESS_DENIED, RATE_LIMITED, TEMPORARY_FAILURE, PARSE_CHANGED, REPEATED_PAGE, DISCONNECTED или USER_PAUSED. Только EXHAUSTED после всех разделов даёт COMPLETE. Остановка по лимиту, отсутствующей пагинации или нераспознанной странице даёт PARTIAL/ERROR.

## 4. Персистентная очередь и модель

Переиспользовать titles(source_id, external_title_id), постоянный UUID, TitleRepository.syncCatalog и таблицы тегов. Неполная карточка каталога сохраняет уже известные подробности тайтла.

Предлагаемые миграции — V10–V12. Их номера сверить с веткой непосредственно перед реализацией.

| Объект | Состав |
| --- | --- |
| catalog_sync_runs | run_id, source_id, mode, state, phase, policy_version, started/finished/heartbeat timestamps, lease_owner, lease_until, attempt_version, next_attempt_at, retry_count, stop_reason, last_error. Один активный run на источник. |
| catalog_sync_checkpoints | run_id+partition PK, cursor JSONB, boundary_ids, pages_committed, source_total NULL, last_page_fingerprint, terminal_evidence. |
| catalog_sync_seen | run_id+title_id PK. Достоверный счётчик уникальных тайтлов при повторах страниц. |
| titles | discovered_at, last_seen_at/run_id, source_created_at, source_updated_at, source_latest_chapter_at, metadata_checked_at, search_document. Дата главы отдельно от изменения метаданных. |
| title_chapter_checks | title_id+language+policy_version PK, state, presence, exact_count NULL, known_count, cursor, checked_at, last_success_at, next_check_at, raw_count, rejected_count, excluded_counts, last_error. |
| source_chapters | title_id+source_chapter_id UNIQUE, logical_key, volume_raw, number_raw, number_numeric NULL, language, translation_group, published_at NULL, page_count NULL, source_locator, access_state, last_seen_run_id. |
| chapter_manifests/pages | Глава, версия, fetched_at, expires_at NULL, упорядоченные индексы, разрешённые upstream-адреса, состояние проб картинок и ошибка. Данные ограничены TTL. |
| catalog_read_snapshots/items | snapshot_id, нормализованный запрос, captured_at, expires_at, источники, порядковые позиции и снимки данных карточек. |

Скрытие тайтла выполняется условием выдачи. Его строка, библиотека, заметки, прогресс и офлайн-копии сохраняются. Неполучение тайтла при одном повторном обходе не подтверждает отсутствие глав.

Worker получает upstream-страницу вне транзакции. В одной транзакции выполняет upsert, сохраняет seen, ставит проверки глав и продвигает checkpoint. Авария до commit приводит к безопасному повтору той же страницы. При возобновлении захватывается просроченная lease. Номер попытки защищает от записи результата старым worker.

Очередь проверки глав отдельно от очереди метаданных. Ошибка одного тайтла/источника не останавливает остальные.

policy_version включает языки, рейтинги, исключённые группы, версию парсера и область авторизации. При изменении политики прежний ноль не используется для скрытия в новом контексте. Старые метаданные остаются доступны, новая проверка начинается с UNVERIFIED. Первичный импорт большого корпуса и проверка всех глав могут длиться дольше одной пользовательской сессии, поэтому оба этапа имеют отдельные счётчики и возобновление.

## 5. Обход источников

### MangaDex

GET /manga с limit до 100, offset, order[createdAt]=asc, includes и рейтингами. Сохранять total, последнюю дату и ID граничной секунды. Живой запрос limit=1&offset=10000 вернул 400 с ограничением from+size<=10 000.

До исчерпания окна переходить к временному участку через createdAtSince. Параметр проверен успешным запросом. Нормализовать даты в UTC, перекрывать границу минимум на секунду и дедуплицировать ID. Для одинакового timestamp хранить offset и все уже увиденные boundary ID.

Переход допустим только при продвижении. Если единственная временная граница не помещается в окно и доступный контракт не позволяет разделить её безопасно, сохранять SOURCE_LIMIT с причиной. Не предполагать существование недокументированного scroll API.

Инкрементальный проход использует updatedAtSince с перекрытием. Повторный полный проход обновляет last_seen_run_id. После реального обхода сравнить unique IDs и терминальные ответы каждого участка.

Feed читать limit=500 с обязательной валидацией offset/total. Исключать внешние/пустые/будущие главы явными includeExternalUrl=0, includeEmptyPages=0, includeFuturePublishAt=0. Сохранять ограничения групп/рейтингов/языка. При достижении окна применять проверенное временное разделение главы по createdAt, иначе PARTIAL. Отсутствующий total считается ошибкой контракта.

### MangaKakalot

Обновить домен и HTML-парсер под фактически проверенный katamanga.com. Разрешать только проверенные домены, нормализовать относительные ссылки.

Последовательно идти от page=1 по валидной next-ссылке. Сохранять номер, fingerprint ID и признак терминальной страницы. Значение 4007 не зашивать. Пустой HTML без распознанной структуры, повтор первой страницы или внезапная 404 в середине дают ошибку/частичный обход.

Каталог изменяется во время обхода. Считать покрытие завершённым проходом изменяемой выдачи на интервале startedAt/finishedAt, хранить уникальные ID и выполнять повторные сверки. Точное состояние сайта на одну секунду этот механизм не обещает.

Список глав с limit=-1 проверить на total/пагинацию и сравнить со счётчиком карточки, если он доступен. Пропущенные строки и отсутствие свидетельства полноты дают PARTIAL. Необычный номер хранится как строка.

### Pepper&Carrot

В одном шаге сохранить единственный тайтл, целиком проверить episodes.json и поддерживаемые RU/EN-переводы. Полнота подтверждается валидным полным индексом.

### MangaLib

Массовый импорт участвует после включения пользователем. Исправление воспроизведённой ошибки CDN распространяется на существующие прямые ссылки.

Каталог брать из JSON с site_id[]=1&page=…, читать meta.has_next_page и current_page. Передавать и проверять meta.seed, если поддерживается. Выбрать подтверждённый стабильный порядок. Если seed не фиксирует выдачу, отметить её изменяемость в покрытии.

Завершать обход при has_next_page=false, отсутствие meta не равно окончанию. SEO-подборку маркировать как ограниченную. Не заменять ею глобальный поиск по сохранённому каталогу.

Сохранять все подходящие ветки и причины restricted_view. Отказ авторизации даёт состояние доступа. Подтверждённо закрытые ветки учитываются в excluded_counts. Невалидные строки не превращаются в достоверный пустой список.

## 6. Достоверность глав и скрытие нулей

Состояние проверки state — UNVERIFIED, CHECKING, PARTIAL, COMPLETE, ERROR. Наличие presence — UNKNOWN, FOUND, NONE.

chapterCount — точное число только для COMPLETE, иначе null. knownChapterCount содержит нижнюю границу. Для COMPLETE+NONE точный count=0 допустим только после полного успешного обхода в данном языковом контексте. Ошибки, 429, 403, неполная пагинация и пропущенные невалидные строки не подтверждают NONE.

chapterCount считает логические главы. editionCount считает выпуски переводчиков. Повторы тома/номера объединяются только при достоверном совпадении. У выпусков без обычного номера отдельные устойчивые ключи.

Для MangaLib ID нового выпуска может быть chapterId~branchId, например 223269~4667. Добавить logicalChapterId и translationGroup. Прежние цифровые ID и прогресс разрешаются в прежний/default перевод.

Повторяемый chapterLanguage задаёт объединение языков чтения. Для общего каталога использовать пересечение с возможностями источника. Источник без выбранного языка исключается с причиной UNSUPPORTED_LANGUAGE в sourceStates. Для явного источника такой запрос даёт 422 LANGUAGE_UNAVAILABLE.

Скрывать только COMPLETE+NONE для всего выбранного языкового контекста. При новой ошибке применять ERROR, сохранив последний успешный снимок отдельно. Не выносить старое пустое состояние как новое подтверждение.

NONE повторно проверять через 24 часа и при обновлении тайтла, найденные главы — через 6 часов/по событию. При появлении главы тайтл возвращается. Состояние изображений UNVERIFIED/SAMPLED_OK/ERROR хранится отдельно. Отказ CDN не уменьшает число глав до нуля.

## 7. Запросы, ошибки и изображения

Стартовые ограничения — два активных источника, одна upstream-операция на источник, до двух изображений на хост. Чтение получает приоритет перед импортом. MangaDex сохраняет интервал минимум 250 мс. Остальные начинают с одного запроса в секунду.

Таймауты connect=4s, metadata read=10s, image read=15s. Ограничить размер тела и число страниц. Проверять Content-Type и структуру JSON/HTML. Для изображений проверять тип и сигнатуру JPEG/PNG/GIF/WebP/AVIF.

Timeout/5xx — максимум три попытки с backoff и jitter. Для 429 сохранять next_attempt_at по Retry-After, включая HTTP-date, и X-RateLimit-Retry-After MangaDex. Перезапуск сохраняет отложенную попытку. 403/смена разметки завершают попытку с диагностикой.

Перенаправления — до трёх, каждый Location проверяется по фиксированным допустимым доменам данного источника. Новый произвольный хост из HTML не считается доверенным.

Для MangaLib добавить проверенный cdnrulib.link, обновлять список кандидатов из constants по site_id=1 и выбирать рабочий CDN по фактическому GET. Сохранять выбор для выпуска, ограничить число альтернатив и обновлять после отказа.

Общий серверный image endpoint разрешает URL по sourceId, тайтлу, главе, языку и индексу из проверенного манифеста. Параметра произвольного URL нет. Требуемые штатные заголовки источника добавляются после подтверждения их необходимости.

При истёкшем адресе/404/410 обновлять манифест один раз, объединять одновременные refresh. При изменении порядка/числа страниц отдавать PAGE_MANIFEST_CHANGED. После неудачи нового адреса отдавать различимую ошибку картинки. MangaDex@Home сохраняет отчёты успеха/отказа.

Повторный 403 CDN MangaKakalot остаётся внешним ограничением и учитывается в отчёте отдельно от исправленного парсинга. Получение списка адресов само по себе не подтверждает чтение.

## 8. Локальный поиск и пагинация

GET /catalog читает PostgreSQL и доступен во время импорта. Карточки получают сохранённую сводку глав без отдельных upstream-запросов.

Поиск по title/title_ru/title_en/альтернативным именам с tsvector и GIN. Русская конфигурация для title_ru, simple для остальных. Теги через title_tags по правилу «все выбранные», сохраняются sourceId/q/tagId и UNKNOWN_TAG.

Справочник тегов читается из сохранённых source_tags и обновляется фоном. Проверка фильтров по существующему локальному справочнику не вызывает обязательный запрос к MangaDex при каждом поиске.

Сортировки relevance, year_asc, year_desc, latest_chapter_desc сохраняются. Relevance вычисляется локально, пустой q сортируется стабильно по названию. Для равных значений tie-breaker sourceId+externalTitleId. NULL в конце. latestChapterAt берётся из дат доступных выпусков в выбранных языках, отсутствие даты остаётся null.

Первый запрос создаёт снимок уже отфильтрованных данных с фиксированными порядком и карточками на 15 минут. Ограничить до 20 активных снимков локального клиента, чистить истёкшие. Следующие порции читаются по непрозрачному cursor.

total — видимые совпадения этого снимка, hasMore вычисляется после серверной фильтрации. Новые поступления появляются после обновления списка. Изменения сортируемых полей не сдвигают строки текущего снимка.

Если после создания снимка подтвердился новый ноль в соответствующем языковом контексте либо изменилось подключение, инвалидировать снимок с 409 CATALOG_SNAPSHOT_STALE. Клиент заново начинает выдачу, сохраняя фильтры. Обычные изменения метаданных и новые тайтлы снимок не инвалидируют.

Так скрытые элементы не дают пропусков и ложного hasMore. Компромисс — во время массовой первоначальной проверки нулей возможен запрос обновить снимок. При необходимости длительной непрерывной пагинации с частой сменой видимости следующая ступень — версионируемая проекция каталога.

## 9. Предлагаемый контракт frontend

Все пути относительно /api/v1. Ошибки сохраняют application/problem+json с code/detail/errors. Добавляются sourceId, stage, retryable и retryAfterSeconds.

### Каталог

GET /catalog?sourceId=mangadex&q=prison&chapterLanguage=ru&chapterLanguage=en&limit=24

sourceId необязателен, q до 120 символов. Повторяемые tagId до десяти, требуют sourceId. Явный sort пока сохраняет требование sourceId. chapterLanguage — повторяемые ru/en, по умолчанию языки источника. limit=1..100, по умолчанию 24. При cursor остальные параметры должны совпадать с исходным запросом.

~~~typescript
type CatalogResponse = {
  items: CatalogTitle[]
  page: {
    limit: number
    total: number
    hasMore: boolean
    nextCursor: string | null
    snapshotId: string
    capturedAt: string
    expiresAt: string
  }
  partial: boolean
  sourceStates: SourceCatalogState[]
}
type CatalogTitle = TitleSummary & {
  chapterAvailability: ChapterAvailability
  latestChapterAt: string | null
}
type ChapterAvailability = {
  state: 'UNVERIFIED' | 'CHECKING' | 'PARTIAL' | 'COMPLETE' | 'ERROR'
  presence: 'UNKNOWN' | 'FOUND' | 'NONE'
  languages: string[]
  chapterCount: number | null
  editionCount: number | null
  knownChapterCount: number
  checkedAt: string | null
  lastSuccessAt: string | null
  nextCheckAt: string | null
  error: SourceError | null
}
type SourceError = {
  code: string
  stage: 'CATALOG' | 'TITLE' | 'CHAPTERS' | 'MANIFEST' | 'IMAGE'
  detail: string
  retryable: boolean
  retryAfterSeconds: number | null
}
type SourceCatalogState = {
  sourceId: string
  metadataState: 'NOT_STARTED' | 'PARTIAL' | 'COMPLETE' | 'ERROR'
  availableTitleCount: number
  collectedTitleCount: number
  verifiedTitleCount: number
  hiddenTitleCount: number
  sourceTotal: number | null
  stopReason: string | null
  lastSuccessAt: string | null
  error: SourceError | null
}
~~~

partial=true означает незавершённый сбор или ошибку участвующего источника. При недоступности всех внешних сайтов локальный каталог возвращает сохранённые items и sourceStates с HTTP 200. До первого сбора — пустой список и NOT_STARTED. При сбое PostgreSQL сохраняется DATABASE_UNAVAILABLE.

Счётчики sourceStates относятся к сохранённому источнику в выбранном языковом контексте. page.total относится к q/tagId текущего снимка. sourceTotal — последнее количество источника в указанных параметрах обхода, может быть null. Нельзя смешивать эти три области счёта.

### Главы

- GET /sources/{sourceId}/titles/{externalTitleId} сохраняет TitleDetail и добавляет chapterAvailability.
- GET .../chapter-count сохраняет путь, возвращает {chapterCount:number|null,availability:ChapterAvailability}. Читает сохранённую сводку.
- GET .../chapters?language=ru&limit=100&cursor=… сохраняет items, добавляет availability и page:{limit,hasMore,nextCursor}.
- SourceChapter сохраняет id/number/title/languages/pageCount. number и pageCount допускают null, добавляются logicalChapterId:string и translationGroup:string|null.
- POST .../chapter-checks ставит проверку в очередь. Ответ 202 {jobId:string,titleId:string,availability:ChapterAvailability}. Повтор активной задачи возвращает прежний jobId. Явная проверка прямой ссылки сохраняет действующее правило доступности отключённого источника.

UNVERIFIED возвращает 200 с items=[] и состоянием ожидания. Пустое состояние UI допустимо при COMPLETE+NONE. При ERROR и сохранённых главах — 200 с items и ошибкой в availability. При ERROR без пригодных данных — 503 CHAPTER_LIST_UNAVAILABLE с расширением availability.

### Страницы

GET .../chapters/{chapterId}/pages?language=ru сохраняет ChapterPages, добавляет manifestVersion:string, fetchedAt:string, expiresAt:string|null.

pages[].imageUrl указывает GET .../chapters/{chapterId}/images/{index}?language=ru&manifestVersion=… . Маршрут MangaDex остаётся прежним. Успех — image/* с nosniff и ограниченным размером, отказ — Problem Details со stage=IMAGE.

GET .../pages?language=ru&refresh=true выполняет один ограниченный refresh. Одновременные запросы объединяются. Изображение также может инициировать один серверный refresh старого манифеста.

Angular получает blob через HttpClient для разбора ошибок HTTP, создаёт object URL и освобождает его. Сохраняются ленивая загрузка и ограничение параллелизма. При PAGE_MANIFEST_CHANGED перечитывается список с сохранением допустимой позиции. _retry не добавляется к внешним подписанным адресам.

### Фоновая синхронизация

| Маршрут | Ответ |
| --- | --- |
| POST /sources/{sourceId}/catalog-sync | Тело {mode:"FULL"|"INCREMENTAL"}. 202 SyncRun, повтор активного запуска возвращает тот же runId. |
| GET /sources/{sourceId}/catalog-sync | {current:SyncRun|null,lastFinished:SyncRun|null,coverage:SourceCatalogState}. |
| POST /sources/{sourceId}/catalog-sync/{runId}/pause | 202 SyncRun, сохранить checkpoint после текущего атомарного шага. |
| POST /sources/{sourceId}/catalog-sync/{runId}/resume | 202 SyncRun, продолжить незавершённую задачу. |

~~~typescript
type SyncRun = {
  runId: string
  sourceId: string
  mode: 'FULL' | 'INCREMENTAL'
  state: 'QUEUED' | 'RUNNING' | 'WAITING_RETRY' | 'PAUSED' | 'COMPLETED' | 'PARTIAL' | 'FAILED'
  phase: 'CATALOG' | 'CHAPTER_CHECKS' | 'FINISHED'
  pagesCommitted: number
  rowsSeen: number
  uniqueTitlesSeen: number
  chaptersCheckedTitles: number
  sourceTotal: number | null
  startedAt: string
  updatedAt: string
  nextRetryAt: string | null
  stopReason: string | null
  error: SourceError | null
}
~~~

При неизвестном total показывать реальные числа и причину остановки. Запуск массового сбора отключённого источника — 409 SOURCE_DISABLED. Метаданные и проверка глав имеют отдельное покрытие.

### Ошибки

| HTTP / code | Поведение frontend |
| --- | --- |
| 400 VALIDATION_ERROR | Исправить параметры/limit/формат cursor. |
| 409 CATALOG_SNAPSHOT_STALE | Начать новый снимок с прежними фильтрами. |
| 410 CATALOG_CURSOR_EXPIRED | Начать новый снимок. |
| 409 SOURCE_DISABLED | Сообщить об отключении источника. |
| 422 UNKNOWN_TAG / CATALOG_OPTIONS_UNSUPPORTED / LANGUAGE_UNAVAILABLE | Существующие сценарии фильтров и языка. |
| 502 SOURCE_BAD_RESPONSE | Формат данных изменился, stage указывает фазу. |
| 503 SOURCE_ACCESS_DENIED / SOURCE_UNAVAILABLE / SOURCE_RATE_LIMITED | Различать отказ доступа, сеть и лимит. Учитывать retryable/retryAfterSeconds. |
| 503 CHAPTER_LIST_UNAVAILABLE | Нет пригодного списка глав, показать ошибку проверки. |
| 503 MANIFEST_UNAVAILABLE | Не получен валидный список страниц. |
| 409 PAGE_MANIFEST_CHANGED | Обновить манифест. |
| 404 PAGE_NOT_FOUND | Индекс отсутствует в актуальном манифесте. |
| 502 IMAGE_BAD_RESPONSE | Неверный MIME/сигнатура/размер, HTML вместо изображения. |
| 503 IMAGE_ACCESS_DENIED / IMAGE_UNAVAILABLE | Отказ CDN или временный сбой картинки. |

## 10. Изменения для frontend-инженера

1. Api.catalog сохраняет page/partial/sourceStates вместо текущего map(data => data.items). Добавляются limit/cursor/chapterLanguage.
2. CatalogFacts читает chapterAvailability. Убирается отдельный запрос глав на карточку. Ноль показывается только при COMPLETE+NONE.
3. Новый снимок при изменении фильтров и 409/410, отмена старых запросов. Фильтры сохраняются в URL.
4. Язык интерфейса/названия и язык главы управляются отдельно.
5. Карточка показывает ожидание, частичный список, подтверждённую пустоту и ошибку. Поддерживает порции глав и перепроверку.
6. Поддержать pageCount=null, logicalChapterId/translationGroup и ID веток с обратной совместимостью старых ссылок.
7. Читалка разбирает ошибки IMAGE, повторяет ограниченно и обновляет манифест.
8. «Обзор» может показывать progress/coverage через ограниченный polling существующего экрана.

Контракт этого документа предлагается для согласования до реализации. Предыдущий frontend-план описывает теги и офлайн-MVP, перечисленные поля расширяют его.

Изменения, требующие совместного выпуска клиента и сервера — nullable chapterCount/pageCount, порционная выдача /chapters вместо полного List и локальный смысл relevance/latest_chapter_desc. Первые items сохраняют прежнее имя поля, но прежний frontend не использует nextCursor. Обновление библиотечных валидаторов и навигации обязательно вместе с пагинацией, чтобы они не принимали первую порцию за все главы.

## 11. Порядок реализации после утверждения

1. Зафиксировать контракт, синхронизировать frontend-план и типы интеграции.
2. Миграции, персистентные задачи и сводки, старые тайтлы получают UNVERIFIED. Сохраняются UUID и библиотека.
3. Исправить подтверждённые ошибки адаптеров. Добавить SourceCatalogPage и ChapterScanResult с complete/cursor/rejectedCount. Существующие List-вызовы остаются совместимыми обёртками.
4. Workers и возобновление. Сначала Pepper&Carrot, затем MangaDex и MangaKakalot. MangaLib участвует при включении.
5. Локальный каталог с фильтрацией NONE, поиском, сортировкой и снимками.
6. Серверные изображения и обновление манифестов с сохранением MangaDex@Home.
7. Обновить Angular по согласованному контракту и запустить первоначальную синхронизацию.
8. Выполнить приёмку и сохранить отчёт с реальными объёмами, причинами остановки и оставшимися ограничениями.

## 12. Приёмка и точечные регрессии

- Первоначальный проход всех актуально подключённых источников. Отчёт collected/checked/hidden/unverified/partial/error, число страниц, sourceTotal, checkpoint, stopReason. Для COMPLETE — терминальные ответы всех разделов и сверка уникальных ID.
- Перезапуск во время HTTP, между upsert/checkpoint и после commit. Продолжение безопасно, старый worker не подтверждает чужую попытку.
- Повторный проход и два одновременных запуска сохраняют уникальность sourceId+externalTitleId и titles.id.
- Полная пустота, частичный ответ, 403, 429, отсутствующий total и пропущенная строка. Только достоверное отсутствие даёт 0 и скрытие. Появившаяся глава возвращает тайтл.
- Логические главы/языки/несколько веток/нечисловые номера и многостраничный feed. До полного успеха chapterCount=null.
- Скрытые записи до/внутри/после границы порции, точные total/hasMore, равные значения сортировки и NULL. Инвалидирование снимка при новом NONE и отключении.
- Недоступный источник сохраняет работу остальных. Retry-After и лимиты повторов соблюдаются, next_attempt_at переживает перезапуск.
- Каждый подключённый источник — минимум три тайтла и по две главы, где корпус позволяет. Для единственного Pepper&Carrot — три эпизода и оба языка. Проверить первое/промежуточное/последнее изображение, MIME, сигнатуру и браузерное отображение.
- Регрессии найденных причин. MangaKakalot 301/новая разметка/HTML вместо JSON. MangaLib смена CDN/пути //manga/. MangaDex внешние главы/окно 10 000/пропавший total. Image endpoint с истёкшим адресом/изменением порядка/HTML вместо JPEG/запрещённым redirect-host.
- Браузерный сценарий «каталог → язык → тайтл → перевод → чтение → восстановление временного сбоя», старые ссылки библиотеки. Maven verify и Angular build.

Итоговый отчёт описывает причины исправлений, изменения API, фактическое покрытие каждого источника, полные/частичные проверки и ограничения. Отказ CDN MangaKakalot остаётся подтверждённым внешним ограничением этой сети на этапе исследования.

## Источники

- [MangaDex OpenAPI](https://gitlab.com/mangadex-pub/mangadex-api-docs/-/raw/main/static/api.yaml) — временные фильтры и параметры исключения глав. Окно 10 000 дополнительно проверено HTTP-запросом.
- [Текущий каталог MangaKakalot](https://www.katamanga.com/manga-list/hot-manga?page=1).
- [Индекс выпусков Pepper&Carrot](https://www.peppercarrot.com/0_sources/episodes.json).
- [LibGroup](https://raw.githubusercontent.com/keiyoushi/extensions-source/main/lib-multisrc/libgroup/src/eu/kanade/tachiyomi/multisrc/libgroup/LibGroup.kt) — варианты API и работа с ветками.
- [Предыдущие проверки адаптеров](mangakakalot-and-mangalib.md), [frontend-план](../../frontend/docs/catalog-and-offline-plan.md).
