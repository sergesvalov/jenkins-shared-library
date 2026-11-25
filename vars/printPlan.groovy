/**
 * Build plan printed by the first stage — the same functions that decide each stage's `when` feed it,
 * so the log says up front what changed and which stages will run (pattern from homeservices).
 *
 *   def needApk() { params.BUILD_ANDROID || changedSinceLastSuccess('android') }
 *   ...
 *   printPlan(
 *       changes: ['android/ changed', 'backend/ unchanged'],
 *       stages: [
 *           [name: 'Tests',   run: true],
 *           [name: 'Deploy',  run: isMain(), detail: 'to 192.168.x.x'],
 *           [name: 'APK',     run: needApk()],
 *       ],
 *       description: 'deploy + apk'   // optional: also set as the build description
 *   )
 */
def call(Map args) {
    def lines = ["BUILD PLAN #${env.BUILD_NUMBER}${env.BRANCH_NAME && env.BRANCH_NAME != 'main' ? " (branch ${env.BRANCH_NAME})" : ''}"]
    if (args.changes) {
        lines << '  Changes:'
        for (def c : args.changes) { lines << "    ${c}" }
    }
    lines << '  Stages:'
    int width = 0
    for (def s : args.stages ?: []) { width = Math.max(width, s.name.toString().length()) }
    for (def s : args.stages ?: []) {
        def mark = s.run ? (s.detail ?: 'yes') : '—'
        lines << "    ${s.name.toString().padRight(width + 2)}${mark}"
    }
    echo(lines.join('\n'))
    if (args.description) {
        currentBuild.description = args.description
    }
}
