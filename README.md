[readme_recode_android.md](https://github.com/user-attachments/files/34818600/readme_recode_android.md)
<div align="center">
  <h1>RE<span style="color:#FF1111;">:</span>code</h1>
  <p><strong>Автономный мобильный ИИ-клиент для вайб-кодинга на Android</strong></p>

  <p>
    <img src="https://img.shields.io/badge/Platform-Android-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android" />
    <img src="https://img.shields.io/badge/Language-Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin" />
    <img src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white" alt="Jetpack Compose" />
    <a href="https://github.com/anxtyy/recode-android/releases">
      <img src="https://img.shields.io/github/v/release/anxtyy/recode-android?style=for-the-badge&color=FF2A2A&label=Download%20APK" alt="Download APK" />
    </a>
  </p>
</div>

---

## О проекте

**Re:Code** — это лёгкий и быстрый open-source инструмент для разработки веб-приложений, игр и скриптов прямо со смартфона. Приложение работает по принципу **BYOK (Bring Your Own Key)**: вы подключаете свой личный API-ключ напрямую к провайдеру без посредников, скрытых подписок и передачи данных на сторонние серверы.

> **Скачать готовую сборку (APK):** перейти в раздел [**Releases**](https://github.com/anxtyy/recode-for-android/releases) и загрузить актуальный `.apk` из блока **Assets**.

---

## Основные возможности

* **Прямая синхронизация моделей с сервером (`GET /models`)**
  * Поддержка ключей **Google Gemini** (`AQ...` / `AIza...`), **DeepSeek** (`sk-...`), **OpenRouter** (`sk-or-...`) и любых **OpenAI-совместимых эндпоинтов**.
  * Автоматическая проверка валидности ключа и загрузка актуального каталога моделей прямо с серверов провайдеров.
  * Мгновенный поиск по сотням моделей и фильтрация по категориям (*Кодерские / Топ*, *Рассуждающие*, *Бесплатные*).
* **Автономная файловая песочница (`Project/`)**
  * Интеграция со Storage Access Framework (SAF): приложение автоматически создаёт корневую директорию `Project/` в выбранной папке устройства и раскладывает сгенерированные файлы по проектам.
  * В боковом меню доступен менеджер проектов: можно создавать новые проекты, просматривать файлы или отправлять существующий проект на доработку нейросети в один клик.
* **Встроенный предпросмотр (`WebView`)**
  * Мгновенный запуск сгенерированных HTML/CSS/JS проектов прямо внутри приложения по кнопке **«Запуск»**.
* **Удобный интерфейс для вайб-кодинга**
  * Полная поддержка **Markdown** (заголовки `###`, **жирный текст**, списки, инлайн-код и кодовые блоки) и сворачиваемых цепочек рассуждений модели.
  * Закрепление важных чатов в боковом меню и сквозной поиск по истории переписок.
  * Гибкая кастомизация: выбор цветовой палитры (включая адаптивную системную тему), масштабирование шрифта и выбор гарнитуры (`Sans`, `Monospace`, `Serif`).

---

## Скриншоты интерфейса

<p align="center">
  <!-- Замени ссылки ниже на свои скриншоты, загрузив их в папку docs/ или перетащив в редактор GitHub -->
  <img src="docs/screenshot_chat.png" width="24%" alt="Главный экран чата" />
  <img src="docs/screenshot_models.png" width="24%" alt="Выбор и поиск моделей" />
  <img src="docs/screenshot_projects.png" width="24%" alt="Меню проектов и чатов" />
  <img src="docs/screenshot_preview.png" width="24%" alt="Предпросмотр проекта" />
</p>

---

## Безопасность и приватность

* **Никаких бэкенд-серверов:** приложение общается исключительно с официальными API выбранного вами ИИ-провайдера.
* **Локальное хранение:** API-ключи, история чатов и настройки профиля сохраняются локально в памяти вашего устройства.

---

## Сборка из исходного кода

1. Склонируйте репозиторий:
   ```bash
   git clone https://github.com/anxtyy/recode-android.git
   ```
2. Откройте проект в **Android Studio** (актуальной версии с поддержкой Kotlin и Jetpack Compose).
3. Дождитесь завершения синхронизации Gradle и запустите сборку на устройстве или через **Build → Build Bundle(s) / APK(s) → Build APK(s)**.

---

## Лицензия

Проект распространяется как Open-Source. Вы можете свободно изучать код, форкать репозиторий и предлагать улучшения через **Pull Requests** или **Issues**.
