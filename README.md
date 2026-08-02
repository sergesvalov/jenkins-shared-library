# Jenkins Shared Library

Эта библиотека содержит переиспользуемые пайплайн-функции (steps) для Jenkins CI.

## Требования

Для работы библиотеки необходимо:
1. Установленный **Docker** на Jenkins-агенте (или мастере), так как большинство функций (`withNodeBuilder`, `withAndroidBuilder`) выполняют сборку внутри изолированных контейнеров.
2. Установленный плагин **Pipeline Utility Steps** в Jenkins (требуется для работы декларативных функций, таких как `declarativePipeline`, использующих чтение YAML конфигураций через `readYaml`).

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

### `signAndroidApk`
Выравнивает (zipalign) и подписывает (apksigner) Android APK. Принимает параметры в виде Map.
Может использоваться в любых пайплайнах, собирающих Android-приложения.

**Параметры:**
* `unsignedApk` - Путь к исходному неподписанному APK (обязательный)
* `signedApk` - Путь, по которому будет сохранен подписанный APK (обязательный)
* `keystore` - Путь к файлу хранилища ключей (.keystore/.jks) (обязательный)
* `storepass` - Пароль от хранилища (обязательный)
* `keyalias` - Алиас ключа (обязательный)
* `keypass` - Пароль от ключа (обязательный)
* `buildTools` - Версия build-tools (по умолчанию `35.0.0`)
* `zipalign` - Путь к zipalign (по умолчанию `/usr/local/bin/zipalign`)

**Пример использования:**
```groovy
signAndroidApk(
    unsignedApk: 'android/app/build/outputs/apk/release/app-release-unsigned.apk',
    signedApk:   'android/app/build/outputs/apk/release/app-release.apk',
    keystore:    'keystore/release.keystore',
    storepass:   'password',
    keyalias:    'release',
    keypass:     'password'
)
)
```

### `deployDockerCompose`
Деплоит приложение через Docker Compose на удаленный сервер по SSH, используя плагин `sshagent`.
Предварительно создает директорию на сервере, копирует туда compose-файл и запускает `docker compose up`. Если передана мапа `envVars`, автоматически генерирует и копирует файл `.env` на сервер.

**Параметры:**
* `credentialsId` - ID SSH-ключа (credentials) в Jenkins (обязательный)
* `user` - Пользователь для подключения по SSH (обязательный)
* `host` - IP или доменное имя сервера (обязательный)
* `dir` - Директория на сервере, куда будет скопирован compose-файл и где будет запущен docker compose (обязательный)
* `composeFile` - Путь к локальному compose-файлу, который нужно отправить на сервер (обязательный)
* `envVars` - (Map) Переменные окружения, которые будут записаны в `.env` файл на сервере (опционально)

**Пример использования:**
```groovy
deployDockerCompose(
    credentialsId: 'my-ssh-key',
    user: 'deploy',
    host: '192.168.1.10',
    dir: '/opt/myapp',
    composeFile: 'compose.yml',
    envVars: [
        'DOCKER_IMAGE': '192.168.1.11:5050/myapp:latest'
    ]
)
```

### `buildCapacitorAndroid`
Выполняет полный цикл сборки Android APK для Capacitor-проектов (Vite-билд, инициализация, фикс `aapt2` для arm64, Gradle-компиляция и подпись). Обернуто в `withAndroidBuilder`.

**Параметры:**
* `buildScript` - Скрипт сборки веб-части (по умолчанию `npm run build:cap`)
* `keystore` - Путь к хранилищу ключей (по умолчанию `keystore/release.keystore`)
* `storepass` - Пароль хранилища
* `keyalias` - Алиас ключа
* `keypass` - Пароль ключа

**Пример использования:**
```groovy
buildCapacitorAndroid(
    buildScript: 'VITE_MODE=capacitor npm run build:cap',
    keystore: 'keystore/release.keystore',
    storepass: 'password',
    keyalias: 'release',
    keypass: 'password'
)
```

### `buildAndPushDockerImage`
Собирает и сразу пушит Docker-образ в реестр (включая тег `latest`). Упрощает стандартный флоу работы с Docker.

**Параметры:**
* `imageName` - Имя образа (обязательный)
* `tag` - Тег образа (по умолчанию `env.BUILD_NUMBER`)
* `context` - Контекст сборки (по умолчанию `.`)
* `extraArgs` - Дополнительные аргументы для `docker build` (опционально)

**Пример использования:**
```groovy
buildAndPushDockerImage(imageName: '192.168.0.222:5050/myapp', tag: 'v1.0.0')
```

### `cleanLocalDockerImages`
Удаляет локальные копии Docker-образов, чтобы освободить место на Jenkins-агенте. Вызывает `docker rmi`, игнорируя ошибки.

**Параметры:**
* `imageName` - Имя образа (обязательный)
* `tag` - Тег образа (опционально)

**Пример использования (обычно в блоке post { always { ... } }):**
```groovy
cleanLocalDockerImages(imageName: '192.168.0.222:5050/myapp', tag: 'v1.0.0')
```

### `remoteDockerLogs`
Подключается к удаленному серверу по SSH и выводит последние логи указанного Docker-контейнера. Отлично подходит для шагов Health Check.

**Параметры:**
* `containerName` - Имя контейнера (обязательный)
* `host` - IP сервера (обязательный)
* `user` - Пользователь SSH (обязательный)
* `credentialsId` - ID SSH-ключа (обязательный)
* `lines` - Количество строк лога (по умолчанию 30)

**Пример использования:**
```groovy
remoteDockerLogs(
    containerName: 'my-bot',
    host: '192.168.0.223',
    user: 'deploy',
    credentialsId: 'my-ssh-key',
    lines: 50
)
```

### `checkHttpEndpoint`
Отправляет HTTP GET запрос (через `curl` с агента) для проверки доступности сервиса. Делает несколько попыток с задержкой.

**Параметры:**
* `url` - Полный URL для проверки (обязательный)
* `retries` - Количество попыток (по умолчанию 3)
* `sleepTime` - Задержка между попытками в секундах (по умолчанию 5)

**Пример использования:**
```groovy
checkHttpEndpoint(url: 'http://192.168.0.223:8000/api/health')
```

### `fixWorkspacePermissions`
Сбрасывает права файлов в текущем Workspace на пользователя, от имени которого работает Jenkins-агент. Незаменим, если вы запускаете тесты или инструменты (playwright, pytest) в Docker-контейнерах, которые создают файлы от имени пользователя `root` (что может вызывать ошибку "Permission denied" при очистке рабочего пространства).

**Пример использования:**
```groovy
post {
    always {
        fixWorkspacePermissions()
    }
}
```

## Как строить пайплайны с этой библиотекой

Использование разделяемой библиотеки позволяет сделать ваши `Jenkinsfile` короткими, декларативными и сфокусированными только на логике конкретного проекта, пряча всю "грязную" работу (Docker-контейнеры, кэши, хаки сборщиков) под капот.

### Базовый шаблон пайплайна

Вот типичный скелет того, как рекомендуется выстраивать этапы сборки:

```groovy
@Library('mylib@main') _

pipeline {
    agent { label 'built-in' }
    
    options {
        skipDefaultCheckout()
    }
    
    environment {
        REGISTRY_IP = '192.168.0.222'
        // ... другие переменные
    }
    
    stages {
        stage('Checkout') {
            steps {
                checkout scm
                // Очистка рабочих директорий
            }
        }
        
        stage('Build Toolchain') {
            steps {
                // Используем buildAndPushIfChanged, чтобы не пересобирать
                // тяжелые образы (Node, Android), если их Dockerfile не изменился
            }
        }
        
        stage('Dependencies & Tests') {
            steps {
                // Оборачиваем шаги в withNodeBuilder для работы в изолированном
                // Node-окружении с кэшированием npm
                withNodeBuilder {
                    sh 'npm ci'
                    sh 'npm run test'
                }
            }
        }
        
        stage('Build Web & Deploy') {
            steps {
                // Сборка статики
                withNodeBuilder {
                    sh 'npm run build:web'
                }
                
                // Деплой через ssh+docker-compose с помощью готового шага
                deployDockerCompose(...)
            }
        }
        
        stage('Build Mobile') {
            steps {
                // Вызов высокоуровневого шага, который делает всё: Vite, Capacitor, Gradle, Zipalign, Apksigner
                buildCapacitorAndroid(...)
                
                // Сохранение артефактов
                archiveArtifacts artifacts: '**/*.apk'
            }
        }
    }
}
```

### Главные принципы
1. **Изоляция сборок**: Никогда не собирайте проекты прямо на хосте Jenkins. Всегда используйте `withNodeBuilder` или `withAndroidBuilder`. Это гарантирует, что сборка не зависит от того, что установлено на сервере.
2. **Кэширование**: Обертки `with*Builder` автоматически подключают именованные тома (`NPM_CACHE_VOLUME` и `GRADLE_CACHE_VOLUME`). Это ускоряет сборку в десятки раз по сравнению с чистой загрузкой пакетов при каждом запуске.
3. **Безопасность**: Скрывайте учетные данные. Передавайте только ID кредов (как в `deployDockerCompose`) или пути к защищенным файлам (`buildCapacitorAndroid`).
4. **DRY (Don't Repeat Yourself)**: Если вы видите, что один и тот же bash-скрипт из 5-10 строк кочует из проекта в проект (как это было с хаком `aapt2` для arm64), выносите его в файл `vars/stepName.groovy` в эту библиотеку.