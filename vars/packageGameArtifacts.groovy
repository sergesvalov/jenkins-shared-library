def call(Map config = [:]) {
    def buildPC = config.buildPC ?: false
    def buildMac = config.buildMac ?: false
    def buildTelegram = config.buildTelegram ?: false
    
    withNodeBuilder {
        if (buildPC) {
            echo "Архивируем ПК-версию..."
            sh "cp PlayGame.bat dist/"
            sh "cd dist && zip -r ../spaceinvasion-pc.zip *"
        }
        
        if (buildMac) {
            echo "Архивируем Mac-версию..."
            sh "cp PlayGame.command dist/"
            sh "chmod +x dist/PlayGame.command"
            sh "cd dist && zip -r ../spaceinvasion-mac.zip *"
        }
        
        if (buildTelegram) {
            echo "Архивируем веб-сборку для Telegram бота..."
            sh "cd dist && zip -r ../spaceinvasion-telegram.zip *"
        }
    }
}
