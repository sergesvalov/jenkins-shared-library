def call(Closure body) {
    docker.image("${env.ANDROID_IMAGE}:${env.ANDROID_IMAGE_TAG}").inside("-u root -v ${env.NPM_CACHE_VOLUME}:/tmp/.npm -v ${env.GRADLE_CACHE_VOLUME}:/root/.gradle") {
        body()
    }
}
