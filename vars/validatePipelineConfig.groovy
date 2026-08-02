def call(Map args = [:]) {
    def configPath = args.configPath ?: 'pipeline-config.yaml'
    
    // Вытаскиваем генератор и схему из ресурсов библиотеки (папка resources/)
    def generatorScript = libraryResource('generate-pipeline.js')
    def schemaJson = libraryResource('pipeline-config.schema.json')
    
    // Сохраняем во временные файлы в workspace
    writeFile file: 'generate-pipeline.cjs', text: generatorScript
    writeFile file: 'pipeline-config.schema.json', text: schemaJson

    echo "🔍 Валидация ${configPath}..."
    
    // Запускаем внутри изолированного Node-окружения
    docker.image('node:22-alpine').inside('-u root') {
        // Устанавливаем зависимости на лету (игнорируем ошибки отсутствия package.json)
        sh 'npm install --no-save ajv yaml diff >/dev/null 2>&1 || true'
        
        // Запуск генератора. Если конфиг инвалидный, Node.js завершится с exit 1,
        // что автоматически уронит Pipeline на этом шаге (sh throw error).
        sh "node generate-pipeline.cjs ${configPath} --dry-run"
    }
}
