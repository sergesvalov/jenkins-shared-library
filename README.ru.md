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

### `signAndroidApk`
Выравнивает (zipalign) и подписывает (apksigner) Android APK.
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
```

### `deployDockerCompose`
Деплоит приложение через Docker Compose на удаленный сервер по SSH, используя плагин `sshagent`. Предварительно создает директорию на сервере, копирует туда compose-файл и запускает `docker compose up`.
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
images:
  - name: "myapp-backend"
    context: "./backend"
migrations:
  service: "backend"
  delay: 20
containers:
  - "myapp_backend"
  - "myapp_db"
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