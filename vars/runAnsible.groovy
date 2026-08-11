def call(Map params = [:]) {
    def inventory = params.inventory ?: 'ansible/inventory.ini'
    def playbook = params.playbook
    def sshCredential = params.sshCredential
    def extraArgs = params.extraArgs ?: ''

    if (!playbook) {
        error("You must specify a 'playbook' parameter for runAnsible.")
    }
    if (!sshCredential) {
        error("You must specify an 'sshCredential' parameter for runAnsible.")
    }

    sshagent(credentials: [sshCredential]) {
        sh """
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
        ansible-playbook -i ${inventory} ${playbook} ${extraArgs}
        """
    }
}
