# Jenkins Shared Library

Эта библиотека содержит переиспользуемые пайплайн-функции (steps) для Jenkins CI.

## Как подключить

В вашем `Jenkinsfile` добавьте импорт в самом начале файла:

```groovy
@Library('mylib') _

pipeline {
    ...
}
```

*(Имя `mylib` должно быть настроено в глобальных конфигурациях вашего Jenkins: Manage Jenkins -> System -> Global Pipeline Libraries).*

## Доступные функции

### `buildAndPushIfChanged`
Собирает и пушит Docker-образ только в том случае, если он еще не существует в реестре (проверка через `docker pull`). Полезно для оптимизации сборки toolchain-образов, когда Dockerfile меняется редко.

**Параметры:**
* `image` (String) - Имя образа (без тега).
* `tag` (String) - Тег (например, хэш от `Dockerfile`).
* `dockerfile` (String) - Путь к `Dockerfile`.
* `label` (String) - Человекочитаемое название для логов.

**Пример использования:**
```groovy
env.NODE_IMAGE_TAG = sh(script: 'sha1sum Dockerfile.build | cut -c1-12', returnStdout: true).trim()
buildAndPushIfChanged(env.NODE_IMAGE, env.NODE_IMAGE_TAG, 'Dockerfile.build', 'Node')
```