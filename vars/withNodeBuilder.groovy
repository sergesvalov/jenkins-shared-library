def call(Closure body) {
    docker.image("${env.NODE_IMAGE}:${env.NODE_IMAGE_TAG}").inside("-u root -v ${env.NPM_CACHE_VOLUME}:/tmp/.npm") {
        body()
    }
}
