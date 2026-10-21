# Jenkins Shared Library

Эта библиотека содержит переиспользуемые пайплайн-функции (steps) для Jenkins CI. Она позволяет сделать ваши `Jenkinsfile` короткими, декларативными и сфокусированными только на логике конкретного проекта, пряча всю "грязную" работу (Docker-контейнеры, кэши, хаки сборщиков) под капот.

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
   - Выберите **Git** (или GitHub) и укажите URL репозитория библиотеки.
   - Укажите Credentials (SSH-ключ), у которого есть доступ на чтение этого репозитория.

### 2. Настройка глобальных переменных и доступов
Пайплайны полагаются на ряд глобальных переменных окружения и учетных данных, которые нужно задать один раз на уровне всего Jenkins.
1. **Глобальные переменные** (Manage Jenkins -> System -> Global properties -> Environment variables):
   - `SERVER_USER` — имя пользователя на целевом сервере (например, `deploy_user`).
   - `REGISTRY_IP` — IP-адрес или домен вашего Docker-реестра (например, `192.168.x.x`).
2. **SSH Ключи** (Manage Jenkins -> Credentials):
   - Создайте **SSH Username with private key** с ID, полностью совпадающим со значением `SERVER_USER`.

---

## Доступные функции

В вашем `Jenkinsfile` добавьте импорт в самом начале файла, чтобы использовать функции:

```groovy
@Library('your-library-name') _
```

### `buildAndPushIfChanged`
Собирает и пушит Docker-образ только в том случае, если он еще не существует в реестре (проверка через `docker pull`). Полезно для оптимизации сборки toolchain-образов.
**Пример использования:**
```groovy
env.NODE_IMAGE_TAG = sh(script: 'sha1sum Dockerfile.build | cut -c1-12', returnStdout: true).trim()
buildAndPushIfChanged(env.NODE_IMAGE, env.NODE_IMAGE_TAG, 'Dockerfile.build', 'Node')
```

### `withNodeBuilder`
Запускает переданный блок кода внутри изолированного Docker-контейнера сборщика (Node.js). Автоматически пробрасывает кэш для npm (через volume `NPM_CACHE_VOLUME`) и запускает контейнер от root для избежания проблем с правами.
**Пример использования:**
```groovy
withNodeBuilder {
    sh 'npm ci --ignore-scripts'
    sh 'npm run build:web'
}
```

### `withAndroidBuilder`
Аналогично `withNodeBuilder`, но запускает блок кода внутри Docker-контейнера для сборки Android-приложений. Пробрасывает сразу два кэша: `NPM_CACHE_VOLUME` для npm и `GRADLE_CACHE_VOLUME` для Gradle.
**Пример использования:**
```groovy
withAndroidBuilder {
    sh 'VITE_MODE=capacitor npm run build:cap'
    sh 'cd android && ./gradlew assembleRelease'
}
```

### `withApkSigningKey`
Выполняет блок с ключом подписи Android-релиза и затем удаляет ключ. **Основной способ**: один общий ключ для всех проектов, хранится в OpenBao. Хранилище ключей, его пароль и алиас берутся из учётных данных Jenkins, которые Jenkins заполняет из OpenBao — библиотека публичная, поэтому в ней **только идентификаторы учётных данных, никаких секретов**. Внутри блока: `APK_KEYSTORE_FILE` (хранилище, восстановленное во временную папку с доступом только владельцу), `APK_KEYSTORE_PASSWORD` (он же пароль ключа), `APK_KEY_ALIAS` и `APK_SIGNING` (`true` или `false`, если подпись пропущена). Передавайте секреты по имени переменной (`docker run -e APK_KEYSTORE_PASSWORD`, `apksigner --ks-pass env:…`), а не в командной строке.
Параметры: `keystoreCredentialsId` (по умолчанию `apk-keystore-base64`), `passwordCredentialsId` (`apk-sign-key`), `aliasCredentialsId` (`apk-key-alias`), `required` (по умолчанию `true`: падать без учётных данных; `false`: собрать без подписи с предупреждением), `keyDir` (куда восстановить хранилище; должно быть видно сборке).
**Пример (Gradle в контейнере):**
```groovy
withApkSigningKey {
    sh '''
        docker run --rm -v "$PWD/android":/app -w /app \
            -v "$APK_KEYSTORE_FILE":/signing/release.keystore:ro \
            -e APK_KEYSTORE_FILE=/signing/release.keystore -e APK_KEYSTORE_PASSWORD -e APK_KEY_ALIAS \
            my-android-builder ./gradlew assembleRelease
    '''
}
```
Со стороны Gradle (`app/build.gradle.kts`): конфигурация подписи `release`, читающая `APK_KEYSTORE_FILE`, `APK_KEYSTORE_PASSWORD` и `APK_KEY_ALIAS` из окружения.

### `withPersistentApkKey`
Для проекта, которому нужен свой ключ (по умолчанию используйте общий — `withApkSigningKey`). Выполняет блок с **постоянным** ключом подписи Android-релиза: хранилище создаётся один раз и лежит в кеше Jenkins (не в git), поэтому все сборки подписаны одним ключом и новый APK ставится обновлением поверх старого. Пароль — из учётных данных Jenkins, которые заполняются из OpenBao; в библиотеке **только идентификаторы учётных данных**. Внутри блока: `APK_KEYSTORE_DIR` (смонтируйте в контейнер сборки), `APK_KEYSTORE_FILE`, `APK_KEYSTORE_PASSWORD` (он же пароль ключа), `APK_KEY_ALIAS`.
Ключ: нет — создаётся; открывается паролем — используется; открывается только `legacyPassword` — один раз перешифровывается паролем из OpenBao (ключ тот же, копия — `release.keystore.bak`, установленные приложения обновляются); не открывается ничем — шаг падает и ключ не трогает (с новым ключом приложение придётся переустановить на всех телефонах — решает человек).
Параметры: `keyDir` и `alias` (обязательные), `passwordCredentialsId` (по умолчанию `apk-sign-key`), `fallbackCredentialsId` (если первых нет или они пустые), `legacyPassword` (прежний пароль ключа — передаёт сам проект, здесь значения по умолчанию нет), `dname` (по умолчанию `CN=<alias>`), `builderImage` (образ с keytool, по умолчанию `mingc/android-build-box:latest`).
**Пример использования:**
```groovy
withPersistentApkKey(keyDir: "${env.WORKSPACE}/../myapp-caches/signing", alias: 'myapp') {
    sh '''
        docker run --rm -v "$PWD/android":/app -w /app -v "$APK_KEYSTORE_DIR":/signing:ro \
            -e APK_KEYSTORE_FILE=/signing/release.keystore -e APK_KEYSTORE_PASSWORD -e APK_KEY_ALIAS \
            my-android-builder ./gradlew assembleRelease
    '''
}
```

### `publishApkForUpdate`
Публикует подписанный APK для автообновления приложения: пишет рядом `latest.json` (`versionName`, `versionCode` = номер сборки, размер, sha256, время сборки, коммит) и копирует оба файла в каталог на сервере по SSH — под временными именами с последующим переименованием, так что приложение не увидит недокачанный APK. Адреса и пользователей передаёт проект.
Параметры: `apk`, `host`, `user`, `credentialsId` (SSH-ключ для `sshagent`), `remoteDir` (все обязательные), `versionName` (по умолчанию — из `android/app/build.gradle.kts`).
```groovy
publishApkForUpdate(apk: 'android/app/build/outputs/apk/release/myapp.apk',
    host: env.PROD_SERVER_IP, user: env.DEPLOY_USER, credentialsId: env.DEPLOY_USER, remoteDir: '/opt/myapp/downloads')
```

### `changedSinceLastSuccess`
Менялось ли что-то в каталоге с последней успешной сборки джобы (`true` на первой сборке). Чтобы пропускать долгие этапы:
```groovy
when { expression { return params.BUILD_ANDROID || changedSinceLastSuccess('android') } }
```

### `removeRootOwnedPaths`
Удаляет результаты сборки, оставленные контейнерами от root (их не может удалить пользователь jenkins, и на них споткнётся следующий checkout), маленьким контейнером; сборку никогда не роняет.
```groovy
post { always { removeRootOwnedPaths(dir: 'android', paths: ['build', 'app/build', '.gradle', '.kotlin']) } }
```

### `signAndroidApk`
Выравнивает (zipalign) и подписывает (apksigner) Android APK.
**Пример использования:**
```groovy
signAndroidApk(
    unsignedApk: 'android/app/build/outputs/apk/release/app-release-unsigned.apk',
    signedApk:   'android/app/build/outputs/apk/release/app-release.apk',
    keystore:    'keystore/release.keystore',
    storepass:   env.APK_KEYSTORE_PASSWORD,  // e.g. inside withApkSigningKey; never a literal
    keyalias:    'release',
    keypass:     env.APK_KEYSTORE_PASSWORD
)
```

### `deployDockerCompose`
Деплоит приложение через Docker Compose на удаленный сервер по SSH, используя плагин `sshagent`. Предварительно создает директорию на сервере, копирует туда compose-файл и запускает `docker compose up`. Если переданы `envVars`, генерирует файл `.env.deploy` на сервере и запускает контейнеры с ключами `--env-file .env --env-file .env.deploy`, чтобы не затирать локальный файл `.env` на сервере.
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
Выполняет полный цикл сборки Android APK для Capacitor-проектов (Vite-билд, инициализация, Gradle-компиляция и подпись). Обернуто в `withAndroidBuilder`.
Подписанный APK называется по имени сервиса: `<service_name>.apk` (из `env.SERVICE_NAME`); другое имя задаётся параметром `apkName`.
**Пример использования:**
```groovy
buildCapacitorAndroid(
    buildScript: 'VITE_MODE=capacitor npm run build:cap',
    keystore: 'keystore/release.keystore',
    storepass: env.APK_KEYSTORE_PASSWORD,
    keyalias: 'release',
    keypass: env.APK_KEYSTORE_PASSWORD
)
```

### `buildAndPushDockerImage`
Собирает и сразу пушит Docker-образ в реестр (включая тег `latest`). Упрощает стандартный флоу работы с Docker.
**Пример использования:**
```groovy
buildAndPushDockerImage(imageName: '192.168.x.x:5050/myapp', tag: 'v1.0.0')
```

### `cleanLocalDockerImages`
Удаляет локальные копии Docker-образов, чтобы освободить место на Jenkins-агенте.
**Пример использования (обычно в блоке post { always { ... } }):**
```groovy
cleanLocalDockerImages(imageName: '192.168.x.x:5050/myapp', tag: 'v1.0.0')
```

### `remoteDockerLogs`
Подключается к удаленному серверу по SSH и выводит последние логи указанного Docker-контейнера.
**Пример использования:**
```groovy
remoteDockerLogs(containerName: 'my-bot', host: '192.168.x.x', user: 'deploy', credentialsId: 'my-ssh-key')
```

### `checkHttpEndpoint`
Отправляет HTTP GET запрос (через `curl` с агента) для проверки доступности сервиса.
**Пример использования:**
```groovy
checkHttpEndpoint(url: 'http://192.168.x.x:8000/api/health')
```

### `fixWorkspacePermissions`
Сбрасывает права файлов в текущем Workspace на пользователя, от имени которого работает Jenkins-агент.
**Пример использования:**
```groovy
post { always { fixWorkspacePermissions() } }
```

---

## Подходы к разработке пайплайнов

Чтобы поддерживать уникальные требования проектов, сохраняя при этом стандартизацию пайплайнов, библиотека предоставляет два основных подхода: **Композиция** (ручной пайплайн) и **Динамический пайплайн** (на основе конфигурации).

### Подход 1: Композиция (Пересоздание пайплайна)

Если проект сильно нестандартный, вы можете полностью отказаться от обертки и написать обычный `Jenkinsfile` прямо в репозитории. Но вместо того, чтобы писать всё с нуля, вы строите свой кастомный процесс, используя низкоуровневые "кубики" (функции) из нашей библиотеки.

**Главные принципы:**
1. **Изоляция сборок**: Никогда не собирайте проекты прямо на хосте Jenkins. Всегда используйте `withNodeBuilder` или `withAndroidBuilder`.
2. **Кэширование**: Обертки `with*Builder` автоматически подключают именованные тома, что ускоряет сборку в десятки раз.
3. **Безопасность**: Передавайте только ID кредов или пути к защищенным файлам.

**Пример кастомного `Jenkinsfile` с использованием функций библиотеки:**

```groovy
@Library('your-library-name@main') _

pipeline {
    agent { label 'built-in' }
    
    stages {
        stage('Custom Build Engine') {
            steps {
                // Используем обертку для изоляции сборки
                withNodeBuilder {
                    sh 'npm run weird-build-process'
                }
            }
        }
        stage('Deploy') {
            steps {
                // Используем готовую функцию деплоя
                deployDockerCompose(
                    credentialsId: 'deploy_user', 
                    host: '10.0.0.1', 
                    dir: '/opt/app', 
                    composeFile: 'compose.yml'
                )
            }
        }
    }
}
```

---

### Подход 2: Динамический пайплайн (Стандарт `declarativePipeline`)

Новый стандарт требует минимизации `Jenkinsfile` в проектах и выноса логики в универсальный шаг `declarativePipeline()`. Конфигурация выносится в файл `pipeline-config.yaml` в корне проекта.

**Пример `Jenkinsfile` (вызов):**
```groovy
@Library('your-library-name@main') _
declarativePipeline(agent: 'built-in')
```

#### Пример конфигурации `pipeline-config.yaml`
**Для Docker-Compose проекта:**
```yaml
service_name: "myapp"
stack_type: "docker-compose"
target_cluster: "prod"
deploy:
  dir: "/opt/myapp"
healthcheck_url: "http://192.168.x.x:8125/health"
images:
  - name: "myapp-backend"
    context: "./backend"
migrations:
  service: "backend"
  delay: 20
containers:
  - "myapp_backend"
  - "myapp_db"
envVars:
  - SECRET_KEY
  - DATABASE_URL
```

**Для Capacitor проекта:**
```yaml
service_name: "my-hybrid-app"
stack_type: "capacitor"
target_cluster: "prod"
deploy:
  host: "10.0.0.1"
  dir: "/opt/myapp"
features:
  - typecheck
  - tests
```
*В конфигурационном файле не должно быть никаких секретных ключей или паролей. Они подтягиваются Jenkins автоматически через глобальные переменные.*

#### Наследование и модификация (`custom_stages`)
Если базовый шаблон (`stack_type`) подходит вам на 90%, вы можете использовать `custom_stages` для внедрения, замены или добавления конкретных шагов без переписывания всего пайплайна.

Доступные стратегии:
* `insert_before: "Имя стадии"` — Вставить шаг до стандартного.
* `insert_after: "Имя стадии"` — Вставить шаг после стандартного.
* `replace: "Имя стадии"` — Полностью заменить стандартный шаг.

**Пример:**
```yaml
service_name: "myapp"
stack_type: "capacitor"
custom_stages:
  - name: "Custom Security Scan"
    insert_before: "Test"
    steps: |
      script {
          echo "Running custom security scan..."
          sh 'npm run scan'
      }
```

#### Серверная валидация и сухой прогон (Dry-Run)
Чтобы ошибки конфигурации отлавливались как можно раньше, `declarativePipeline` оснащен встроенной серверной проверкой. В самом начале сборки Jenkins валидирует ваш `pipeline-config.yaml` по JSON Schema. Если описание содержит ошибку, сборка падает немедленно.

Если вы хотите проверить, как будет выглядеть сгенерированный пайплайн без фактической сборки и деплоя, запустите джобу с параметром **`VALIDATE_ONLY`**. Пайплайн распечатает `Jenkinsfile` в логи и успешно завершится.

---

## Лицензия

Этот проект распространяется под лицензией MIT — подробности смотрите в файле [LICENSE](LICENSE). Вы можете свободно использовать, изменять и распространять этот код при условии, что вы сохраните уведомление об авторских правах и добавите ссылку на этот репозиторий.