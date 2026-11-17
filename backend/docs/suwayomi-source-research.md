# Как Suwayomi получает мангу из внешних источников

Suwayomi-Server запускает установленные расширения Mihon/Tachiyomi. Расширение знает, куда отправлять запросы и как извлекать данные конкретного сайта. Сервер использует общий интерфейс источника, ведёт библиотеку и обслуживает читалку. Источники устанавливаются через репозитории расширений. [Suwayomi-Server](https://github.com/Suwayomi/Suwayomi-Server#what-is-suwayomi) · [Установка расширений](https://github.com/Suwayomi/Suwayomi-Server/blob/master/docs/Getting-Extensions.md)

## MangaKakalot в Keiyoushi

Расширение `Mangakakalot` наследует общий Kotlin-класс `MangaBox`. Оно уточняет правила получения идентификатора из адреса тайтла и настройки HTTP-клиента. Конфигурация просмотренной версии указывает зеркала `kakamanga.com` и `mangakakalove.com`. Это перечень из исходников. Доступность зеркал в этой проверке не измерялась. [Класс расширения](https://raw.githubusercontent.com/keiyoushi/extensions-source/main/src/en/mangakakalot/src/eu/kanade/tachiyomi/extension/en/mangakakalot/Mangakakalot.kt) · [Конфигурация](https://raw.githubusercontent.com/keiyoushi/extensions-source/main/src/en/mangakakalot/build.gradle.kts)

| Что нужно | Что разбирает расширение |
| --- | --- |
| Каталог и поиск | HTML выдачи. Через Jsoup и CSS-селекторы извлекаются название, URL и обложка, а также признак следующей страницы. |
| Карточка | HTML тайтла. Из него извлекаются описание, автор, жанры и статус публикации. |
| Главы | JSON внутреннего эндпоинта `/api/manga/{slug}/chapters`. Из записей берутся название, номер, дата и идентификатор главы. |
| Страницы | HTML читалки. Из текста встроенного скрипта извлекаются список CDN и пути изображений. Если массива изображений нет, расширение читает адреса из тегов `img` контейнера читалки. На выходе получается упорядоченный список страниц. |

[Общий парсер MangaBox](https://raw.githubusercontent.com/keiyoushi/extensions-source/main/lib-multisrc/mangabox/src/eu/kanade/tachiyomi/multisrc/mangabox/MangaBox.kt)

Suwayomi передаёт главу расширению через `getPageList`. Сервер сохраняет индексы и ссылки в своей таблице страниц. Это метаданные для чтения. Локальная копия изображений появляется при скачивании главы. [Как сервер получает и хранит список страниц](https://raw.githubusercontent.com/Suwayomi/Suwayomi-Server/master/server/src/main/kotlin/suwayomi/tachidesk/manga/impl/chapter/ChapterForDownload.kt)

## Что уже есть в «Кадре»

Интерфейс `MangaSource` уже включает поиск, карточку, главы и страницы. MangaDex получает JSON официального API. Pepper&Carrot использует Jsoup для разбора HTML. Для нового источника можно реализовать тот же интерфейс и сохранить действующие REST-маршруты Angular. Наличие JSON-эндпоинта у сайта говорит о формате данных, а доступность такого эндпоинта и условия использования проверяются отдельно. [Интерфейс источника](../src/main/java/io/github/mangareader/source/MangaSource.java) · [Адаптер Pepper&Carrot](../src/main/java/io/github/mangareader/source/peppercarrot/PepperCarrotSource.java)
