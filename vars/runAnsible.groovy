def call(Map params = [:]) {
    def inventory = params.inventory ?: 'ansible/inventory.ini'
    def playbook = params.playbook
    def sshCredential = params.sshCredential
    def extraArgs = params.extraArgs ?: ''
    def vaultCredential = params.vaultCredential ?: 'ansible-vault-pass'

    if (!playbook) {
        error("You must specify a 'playbook' parameter for runAnsible.")
    }
    if (!sshCredential) {
        error("You must specify an 'sshCredential' parameter for runAnsible.")
    }

    sshagent(credentials: [sshCredential]) {
        withCredentials([string(credentialsId: vaultCredential, variable: 'VAULT_PASS')]) {
            sh """
            # Гарантируем удаление файла с паролем при любом исходе
            trap 'rm -f .vault_pass' EXIT
            echo "$VAULT_PASS" > .vault_pass

            # Устанавливаем Ansible локально в папке проекта, чтобы не зависеть от сервера Jenkins
            if ! command -v ansible-playbook &> /dev/null; then
                python3 -m venv ansible-venv
                . ansible-venv/bin/activate
                pip install ansible
            else
                echo "Ansible уже установлен глобально"
            fi
            
            # Запускаем плейбук (используем venv если создали)
            if [ -d "ansible-venv" ]; then
                . ansible-venv/bin/activate
            fi
            ansible-playbook -i ${inventory} ${playbook} --vault-password-file .vault_pass ${extraArgs}
            """
        }
    }
}
