// Runs ansible-playbook with the vault password from Jenkins credentials and an SSH key in an agent.
//
// Usage:
//   runAnsible(playbook: 'ansible/site.yml', sshCredential: 'serge')
//   runAnsible(playbook: 'ansible/site.yml', sshCredential: 'serge',
//              extraArgs: '--tags samba --skip-tags "setup"', quiet: true)
//
// Options:
//   playbook          playbook path (required)
//   sshCredential     SSH key credentials for sshagent (required)
//   inventory         inventory path (default 'ansible/inventory.ini')
//   extraArgs         extra ansible-playbook arguments, shell-quoted as on a command line
//   vaultCredential   secret text with the vault password (default 'ansible-vault-pass')
//   quiet             true: print only changed/failed tasks and the recap (no "ok:" lines,
//                     no per-task timing) - for CI logs that should show what actually changed
//
// Uses the project's ansible-venv when present (its bin/ goes first on PATH, like activate,
// without tracing the activate script into the log), otherwise ansible-playbook on PATH,
// otherwise creates ansible-venv with pip install ansible. Values reach the shell through
// environment variables, not Groovy interpolation, so the vault password never appears in
// the script text.
def call(Map params = [:]) {
    def playbook = params.playbook
    def sshCredential = params.sshCredential
    if (!playbook) {
        error("You must specify a 'playbook' parameter for runAnsible.")
    }
    if (!sshCredential) {
        error("You must specify an 'sshCredential' parameter for runAnsible.")
    }
    def envVars = [
        "RA_INVENTORY=${params.inventory ?: 'ansible/inventory.ini'}",
        "RA_PLAYBOOK=${playbook}",
        "RA_EXTRA=${params.extraArgs ?: ''}",
    ]
    if (params.quiet) {
        envVars += ['ANSIBLE_DISPLAY_OK_HOSTS=false', 'ANSIBLE_DISPLAY_SKIPPED_HOSTS=false',
                    'ANSIBLE_CALLBACKS_ENABLED=ansible.posix.timer']
    }

    sshagent(credentials: [sshCredential]) {
        withCredentials([string(credentialsId: params.vaultCredential ?: 'ansible-vault-pass', variable: 'VAULT_PASS')]) {
            withEnv(envVars) {
                sh '''
                    set +x
                    trap 'rm -f .vault_pass' EXIT
                    ( umask 077; printf '%s\\n' "$VAULT_PASS" > .vault_pass )
                    if [ ! -x ansible-venv/bin/ansible-playbook ] && ! command -v ansible-playbook >/dev/null 2>&1; then
                        python3 -m venv ansible-venv && ansible-venv/bin/pip install -q ansible
                    fi
                    if [ -x ansible-venv/bin/ansible-playbook ]; then
                        PATH="$PWD/ansible-venv/bin:$PATH"; export PATH
                    fi
                    eval "set -- $RA_EXTRA"
                    echo "+ ansible-playbook -i $RA_INVENTORY $RA_PLAYBOOK $RA_EXTRA"
                    ansible-playbook -i "$RA_INVENTORY" "$RA_PLAYBOOK" --vault-password-file .vault_pass "$@"
                '''
            }
        }
    }
}
