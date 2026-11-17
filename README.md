# manga-reader

Локальная веб-читалка манги и комиксов с русскоязычным интерфейсом «Кадр». В ней можно искать тайтлы по источникам, читать главы и вести личную библиотеку.

## Возможности

- Подключение Pepper&Carrot, MangaDex, MangaLib и MangaKakalot через «Обзор → Расширения».
- Поиск по выбранному источнику. Для MangaDex доступны теги и сортировка, а параметры поиска сохраняются в ссылке.
- Карточки тайтлов, читалка с выбором перевода и переключатель RU/EN для названий из API.
- Библиотека со статусами, заметками и сохранённой позицией чтения.
- Скачивание глав Pepper&Carrot по переводам. Готовые главы открываются в разделе «Библиотека → Скачанное» без внешнего интернета при запущенных локальных сервисах.

Работа онлайн-источников зависит от доступности их сайтов и серверов изображений. Результаты проверок MangaLib и MangaKakalot собраны в [отчёте по источникам](backend/docs/mangakakalot-and-mangalib.md).

## Структура

| Папка | Содержимое |
| --- | --- |
| [frontend/](frontend/README.md) | Angular 21, интерфейс «Кадр», клиент REST API, макеты и скриншоты |
| [backend/](backend/README.md) | Java 21, Spring Boot, PostgreSQL, миграции Flyway и адаптеры источников |

## Локальный запуск в Windows PowerShell

Понадобятся JDK 21, Node.js 24 LTS и запущенный Docker Desktop. Для бэкенда и фронтенда откройте отдельные окна PowerShell в корне репозитория.

### 1. PostgreSQL и Spring Boot

~~~powershell
Set-Location backend
if (-not (Test-Path -LiteralPath .env)) {
    Copy-Item .env.example .env
}
docker compose up -d --wait postgres
Get-Content -LiteralPath .env | ForEach-Object {
    if ($_ -match '^(POSTGRES_DB|POSTGRES_USER|POSTGRES_PASSWORD|POSTGRES_PORT)=(.*)$') {
        [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
    }
}
.\mvnw.cmd spring-boot:run
~~~

### 2. Angular

~~~powershell
Set-Location frontend
npm ci
npm start
~~~

Откройте [каталог «Кадра»](http://127.0.0.1:4200/catalog). Angular перенаправляет запросы `/api/**` на Spring Boot, который по умолчанию слушает `127.0.0.1:8080`.

## Сборка и тесты

| Папка | Команда | Результат |
| --- | --- | --- |
| `frontend/` | `npm run build` | Сборка Angular в `dist/kadr/browser/` |
| `backend/` | `.\mvnw.cmd -B -ntp verify` | Тесты и JAR в `target/`. Для интеграционных тестов нужен Docker. |

Подробности о маршрутах, переменных окружения и проверках приведены в [документации фронтенда](frontend/README.md) и [документации API](backend/README.md).
