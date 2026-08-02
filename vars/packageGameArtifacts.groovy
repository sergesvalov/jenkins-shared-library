def call(Map config = [:]) {
    def buildPC = config.buildPC ?: false
    def buildMac = config.buildMac ?: false
    def buildTelegram = config.buildTelegram ?: false
    
    withNodeBuilder {
        if (buildPC) {
            echo "Archiving PC build..."
            sh "cp PlayGame.bat dist/"
            sh "cd dist && zip -r ../spaceinvasion-pc.zip *"
        }
        
        if (buildMac) {
            echo "Archiving Mac build..."
            sh "cp PlayGame.command dist/"
            sh "chmod +x dist/PlayGame.command"
            sh "cd dist && zip -r ../spaceinvasion-mac.zip *"
        }
        
        if (buildTelegram) {
            echo "Archiving web build for Telegram bot..."
            sh "cd dist && zip -r ../spaceinvasion-telegram.zip *"
        }
    }
}
