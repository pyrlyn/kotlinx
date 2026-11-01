fun main() {
    println(isExperimentalMM())
    println(Platform.memoryModel)
    println(Platform.memoryModel == MemoryModel.EXPERIMENTAL)
}
