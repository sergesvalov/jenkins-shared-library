def call() {
    echo "Fixing workspace permissions..."
    sh 'docker run --rm -v $(pwd):/workspace alpine chown -R $(id -u):$(id -g) /workspace || true'
}
