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

### `withNodeBuilder`
Запускает переданный блок кода (Closure) внутри изолированного Docker-контейнера сборщика (Node.js). 
Автоматически пробрасывает кэш для npm (через volume `NPM_CACHE_VOLUME`) и запускает контейнер от root для избежания проблем с правами на примонтированные директории.

**Ожидает переменные окружения:**
* `env.NODE_IMAGE` - базовое имя образа сборщика
* `env.NODE_IMAGE_TAG` - тег образа сборщика
* `env.NPM_CACHE_VOLUME` - имя Docker volume для кэширования npm

**Пример использования:**
```groovy
withNodeBuilder {
    sh 'npm ci --ignore-scripts'
    sh 'npm run build:web'
}
```

### `withAndroidBuilder`
Аналогично `withNodeBuilder`, но запускает блок кода внутри Docker-контейнера для сборки Android-приложений. 
Пробрасывает сразу два кэша: `NPM_CACHE_VOLUME` для npm и `GRADLE_CACHE_VOLUME` для Gradle, что критично для скорости пересборки. Запускается от имени root.

**Ожидает переменные окружения:**
* `env.ANDROID_IMAGE` - базовое имя образа Android-сборщика
* `env.ANDROID_IMAGE_TAG` - тег образа Android-сборщика
* `env.NPM_CACHE_VOLUME` - имя Docker volume для кэширования npm
* `env.GRADLE_CACHE_VOLUME` - имя Docker volume для кэширования Gradle

**Пример использования:**
```groovy
withAndroidBuilder {
    sh 'VITE_MODE=capacitor npm run build:cap'
    sh 'npx cap sync android'
    sh 'cd android && ./gradlew assembleRelease'
}
```