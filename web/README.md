# Веб-версия iban4j

Страница, на которой можно собрать и проверить IBAN и BIC прямо в браузере.
Внутри работает настоящая Java-библиотека iban4j: её компилирует в JavaScript
[TeaVM](https://teavm.org).

| Файл | Что это |
|---|---|
| `src/main/java/org/iban4j/web/IbanWeb.java` | Мост: функции, которые вызывает страница |
| `src/main/webapp/index.html` | Сама страница |
| `pom.xml` | Сборка: исходники берутся из `../src/main/java` |
| `../.github/workflows/pages.yml` | Автосборка и публикация на GitHub Pages |

## Собрать у себя

```bash
./mvnw -f web/pom.xml package
```

Результат — папка `web/target/site/`. Открывать её нужно через локальный сервер
(браузер не загружает модули с `file://`), например:

```bash
cd web/target/site && python3 -m http.server 8000
# открыть http://localhost:8000
```

## Публикация на GitHub Pages

1. Settings → Pages → Source: **GitHub Actions**.
2. Actions → разрешить запуск workflow (для форка).
3. Любой push в `master` пересобирает сайт. Адрес: `https://<ник>.github.io/iban4j/`.
