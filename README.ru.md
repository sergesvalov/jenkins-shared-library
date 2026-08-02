# Jenkins Shared Library

Эта библиотека содержит переиспользуемые пайплайн-функции (steps) для Jenkins CI.

## Требования и Плагины

Для работы библиотеки необходимо, чтобы в вашем Jenkins были установлены следующие плагины (Manage Jenkins -> Plugins):
1. **Pipeline Utility Steps** — критически важен, используется функцией `declarativePipeline` для чтения конфигурации из `pipeline-config.yaml` (`readYaml`).
2. **SSH Agent Plugin** — используется функцией `deployDockerCompose` для подключения к удаленным серверам по SSH без передачи паролей в открытом виде.
3. **Docker Pipeline** (и установленный Docker Engine на самом Jenkins-агенте) — для сборки образов (`buildAndPushDockerImage`) и запуска изолированных агентов (`withNodeBuilder`, `withAndroidBuilder`).

## Настройка Jenkins

### 1. Подключение библиотеки
Чтобы использовать эту библиотеку во всех ваших проектах:
1. Зайдите в **Manage Jenkins -> System**.
2. Найдите блок **Global Pipeline Libraries**.
3. Добавьте новую библиотеку:
   - **Name**: `your-library-name` (именно это имя используется в вызове `@Library('your-library-name')`).
   - **Default version**: `main` (или ветка/тег по умолчанию).
   - Выберите **Git** (или GitHub) и укажите URL репозитория библиотеки (например, `git@github.com:sergesvalov/jenkins-shared-library.git`).
   - Укажите Credentials (SSH-ключ), у которого есть доступ на чтение этого репозитория.

### 2. Настройка глобальных переменных и доступов
Пайплайны (в особенности `declarativePipeline`) полагаются на ряд глобальных переменных окружения и учетных данных, которые нужно задать один раз на уровне всего Jenkins.

1. **Глобальные переменные** (Manage Jenkins -> System -> Global properties -> Environment variables):
   - `SERVER_USER` — имя пользователя на целевом сервере (например, `deploy_user`). *Важно: пайплайн ожидает, что в Jenkins созданы SSH-credentials (ключ) с ID, полностью совпадающим с этим значением!*
   - `REGISTRY_IP` — IP-адрес или домен вашего Docker-реестра (например, `192.168.x.x`). Если не задано, fallback на `127.0.0.1`.
   - `PROD_SERVER_IP` (опционально) — IP-адрес продакшен сервера по умолчанию для кластера `prod`.

2. **SSH Ключи** (Manage Jenkins -> Credentials):
   - Создайте **SSH Username with private key**.
   - **ID**: должен совпадать со значением `SERVER_USER` (например, `deploy_user`).
   - Вставьте приватный ключ, который имеет доступ на целевые серверы, куда будет деплоиться код.

## Как подключить

В вашем `Jenkinsfile` добавьте импорт в самом начале файла:

```groovy
@Library('your-library-name') _

pipeline {
    ...
}
```

*(Имя `your-library-name` должно быть настроено в глобальных конфигурациях вашего Jenkins: Manage Jenkins -> System -> Global Pipeline Libraries).*

## Доступные функции

Краткая сводка всех функций (steps), доступных в библиотеке:
- `buildAndPushDockerImage` — Собирает и пушит Docker-образ в реестр.
- `buildAndPushIfChanged` — Собирает Docker-образ, только если его еще нет в реестре.
- `buildCapacitorAndroid` — Полный цикл сборки Android APK для Capacitor.
- `capacitorPipeline` — Стандартный пайплайн для сборки Capacitor-приложений.
- `checkHttpEndpoint` — Проверяет доступность HTTP-эндпоинта.
- `cleanLocalDockerImages` — Удаляет локальные Docker-образы.
- `declarativePipeline` — Универсальный пайплайн на базе `pipeline-config.yaml`.
- `deployDockerCompose` — Деплой приложения через Docker Compose по SSH.
- `dockerComposePipeline` — Стандартный пайплайн для Docker Compose проектов.
- `fixWorkspacePermissions` — Восстанавливает права на файлы в рабочей директории.
- `localDockerComposeDeploy` — Локальный деплой через Docker Compose.
- `packageGameArtifacts` — Архивация артефактов игры (PC, Mac, Web).
- `remoteDockerLogs` — Чтение логов Docker-контейнера с удаленного сервера.
- `runAlembicMigrations` — Запуск миграций БД (Alembic) в Docker.
- `signAndroidApk` — Выравнивание (zipalign) и подпись (apksigner) Android APK.
- `withAndroidBuilder` — Запуск кода внутри контейнера для сборки Android.
- `withNodeBuilder` — Запуск кода внутри Node.js-контейнера.

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
    host: '192.168.x.x',
    dir: '/opt/myapp',
    composeFile: 'compose.yml',
    envVars: [
        'DOCKER_IMAGE': '192.168.x.x:5050/myapp:latest'
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
buildAndPushDockerImage(imageName: '192.168.x.x:5050/myapp', tag: 'v1.0.0')
```

### `cleanLocalDockerImages`
Удаляет локальные копии Docker-образов, чтобы освободить место на Jenkins-агенте. Вызывает `docker rmi`, игнорируя ошибки.

**Параметры:**
* `imageName` - Имя образа (обязательный)
* `tag` - Тег образа (опционально)

**Пример использования (обычно в блоке post { always { ... } }):**
```groovy
cleanLocalDockerImages(imageName: '192.168.x.x:5050/myapp', tag: 'v1.0.0')
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
    host: '192.168.x.x',
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
checkHttpEndpoint(url: 'http://192.168.x.x:8000/api/health')
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
@Library('your-library-name@main') _

pipeline {
    agent { label 'built-in' }
    
    options {
        skipDefaultCheckout()
    }
    
    environment {
        REGISTRY_IP = '192.168.x.x'
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

### Использование `declarativePipeline` (Новый стандарт)

Новый стандарт требует минимизации `Jenkinsfile` в проектах и выноса логики в универсальный шаг `declarativePipeline()`. Конфигурация выносится в файл `pipeline-config.yaml` в корне проекта.

Пример `Jenkinsfile` (вызов):
```groovy
@Library('your-library-name@main') _
declarativePipeline(agent: 'built-in')
```
*(Параметр `agent` является обязательным, вы можете указать любой доступный лейбл Jenkins-узла, например `agent: 'my-custom-node'`)*

#### Пример конфигурации `pipeline-config.yaml` для мульти-контейнерного проекта
Складывается в корневой директории вашего проекта. Позволяет собирать несколько образов (backend, frontend) и автоматически запускать БД миграции.

```yaml
service_name: "myapp"
stack_type: "docker-compose"
target_cluster: "prod"
deploy:
  dir: "/opt/myapp" # Папка на целевом сервере для деплоя
images:
  - name: "myapp-backend"
    context: "./backend"
  - name: "myapp-frontend"
    context: "./frontend"
migrations:
  service: "backend"
  delay: 20 # Задержка перед запуском alembic upgrade head
containers:
  - "myapp_backend"
  - "myapp_frontend"
  - "myapp_db"
```

#### Пример конфигурации `pipeline-config.yaml` для мобильного/веб проекта (`stack_type: "capacitor"`)
Складывается в корневой директории вашего проекта.
```yaml
service_name: "my-hybrid-app"
stack_type: "capacitor"
target_cluster: "prod"
deploy:
  # IP-адрес или домен целевого сервера для деплоя
  # (Реальный адрес здесь скрыт в целях безопасности)
  host: "<IP-АДРЕС_СЕРВЕРА>"
  dir: "/opt/myapp"
  web_port: 7979
features:
  - typecheck
  - tests
  - e2e
```

**О безопасности данных:**
*В конфигурационном файле `pipeline-config.yaml` не должно быть никаких секретных ключей, паролей, или учетных данных (credentials). Учетные данные и ключи для доступа к серверам или сертификатам Android подтягиваются Jenkins автоматически из защищенного хранилища (Jenkins Credentials) через глобальные переменные (например, `SSH_CREDS_ID`, `SERVER_USER`). Файл конфигурации содержит только общую структуру деплоя.*