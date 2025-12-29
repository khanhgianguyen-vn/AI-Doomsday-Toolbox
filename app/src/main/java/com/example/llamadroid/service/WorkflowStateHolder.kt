package com.example.llamadroid.service

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * State holder for Transcribe+Summary workflow
 * This singleton persists workflow state across navigation
 */
object WorkflowStateHolder {
    
    // ===== Transcribe+Summary Workflow State =====
    
    // Audio source
    private val _audioUri = MutableStateFlow<Uri?>(null)
    val audioUri: StateFlow<Uri?> = _audioUri.asStateFlow()
    
    private val _audioPath = MutableStateFlow<String?>(null)
    val audioPath: StateFlow<String?> = _audioPath.asStateFlow()
    
    private val _savedRecordingPath = MutableStateFlow<String?>(null)
    val savedRecordingPath: StateFlow<String?> = _savedRecordingPath.asStateFlow()
    
    // Progress
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    
    private val _step = MutableStateFlow("")
    val step: StateFlow<String> = _step.asStateFlow()
    
    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()
    
    // Results
    private val _transcriptionText = MutableStateFlow("")
    val transcriptionText: StateFlow<String> = _transcriptionText.asStateFlow()
    
    private val _summaryText = MutableStateFlow("")
    val summaryText: StateFlow<String> = _summaryText.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    
    // Recording state
    private val _showRecordingDialog = MutableStateFlow(false)
    val showRecordingDialog: StateFlow<Boolean> = _showRecordingDialog.asStateFlow()
    
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()
    
    private val _recordingSeconds = MutableStateFlow(0)
    val recordingSeconds: StateFlow<Int> = _recordingSeconds.asStateFlow()
    
    // ===== Update functions =====
    
    fun setAudioUri(uri: Uri?) {
        _audioUri.value = uri
    }
    
    fun setAudioPath(path: String?) {
        _audioPath.value = path
    }
    
    fun setSavedRecordingPath(path: String?) {
        _savedRecordingPath.value = path
    }
    
    fun setIsRunning(running: Boolean) {
        _isRunning.value = running
    }
    
    fun setStep(step: String) {
        _step.value = step
    }
    
    fun setProgress(progress: Float) {
        _progress.value = progress
    }
    
    fun setTranscriptionText(text: String) {
        _transcriptionText.value = text
    }
    
    fun setSummaryText(text: String) {
        _summaryText.value = text
    }
    
    fun setError(error: String?) {
        _error.value = error
    }
    
    fun setShowRecordingDialog(show: Boolean) {
        _showRecordingDialog.value = show
    }
    
    fun setIsRecording(recording: Boolean) {
        _isRecording.value = recording
    }
    
    fun setRecordingSeconds(seconds: Int) {
        _recordingSeconds.value = seconds
    }
    
    fun reset() {
        _audioUri.value = null
        _audioPath.value = null
        _savedRecordingPath.value = null
        _isRunning.value = false
        _step.value = ""
        _progress.value = 0f
        _transcriptionText.value = ""
        _summaryText.value = ""
        _error.value = null
        _showRecordingDialog.value = false
        _isRecording.value = false
        _recordingSeconds.value = 0
    }
    
    fun onWorkflowComplete(transcript: String, summary: String) {
        _isRunning.value = false
        _transcriptionText.value = transcript
        _summaryText.value = summary
        _progress.value = 1f
        _step.value = "Complete!"
    }
}

object Txt2ImgWorkflowStateHolder {
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    
    private val _step = MutableStateFlow("")
    val step: StateFlow<String> = _step.asStateFlow()
    
    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()
    
    private val _resultPath = MutableStateFlow<String?>(null)
    val resultPath: StateFlow<String?> = _resultPath.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    
    fun setIsRunning(running: Boolean) { _isRunning.value = running }
    fun setStep(step: String) { _step.value = step }
    fun setProgress(progress: Float) { _progress.value = progress }
    fun setResultPath(path: String?) { _resultPath.value = path }
    fun setError(error: String?) { _error.value = error }
    
    fun reset() {
        _isRunning.value = false
        _step.value = ""
        _progress.value = 0f
        _resultPath.value = null
        _error.value = null
    }
}

/**
 * State holder for AudioTranscriptionScreen
 * Persists transcription state across navigation
 */
object TranscriptionStateHolder {
    private val _audioPath = MutableStateFlow<String?>(null)
    val audioPath: StateFlow<String?> = _audioPath.asStateFlow()
    
    private val _selectedLanguage = MutableStateFlow("auto")
    val selectedLanguage: StateFlow<String> = _selectedLanguage.asStateFlow()
    
    private val _translateToEnglish = MutableStateFlow(false)
    val translateToEnglish: StateFlow<Boolean> = _translateToEnglish.asStateFlow()
    
    private val _outputSrt = MutableStateFlow(true)
    val outputSrt: StateFlow<Boolean> = _outputSrt.asStateFlow()
    
    private val _outputTxt = MutableStateFlow(true)
    val outputTxt: StateFlow<Boolean> = _outputTxt.asStateFlow()
    
    private val _outputVtt = MutableStateFlow(false)
    val outputVtt: StateFlow<Boolean> = _outputVtt.asStateFlow()
    
    private val _outputJson = MutableStateFlow(false)
    val outputJson: StateFlow<Boolean> = _outputJson.asStateFlow()
    
    private val _result = MutableStateFlow<String?>(null)
    val result: StateFlow<String?> = _result.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    
    fun setAudioPath(path: String?) { _audioPath.value = path }
    fun setSelectedLanguage(lang: String) { _selectedLanguage.value = lang }
    fun setTranslateToEnglish(translate: Boolean) { _translateToEnglish.value = translate }
    fun setOutputSrt(enabled: Boolean) { _outputSrt.value = enabled }
    fun setOutputTxt(enabled: Boolean) { _outputTxt.value = enabled }
    fun setOutputVtt(enabled: Boolean) { _outputVtt.value = enabled }
    fun setOutputJson(enabled: Boolean) { _outputJson.value = enabled }
    fun setResult(text: String?) { _result.value = text }
    fun setError(msg: String?) { _error.value = msg }
    
    fun reset() {
        _audioPath.value = null
        _selectedLanguage.value = "auto"
        _translateToEnglish.value = false
        _outputSrt.value = true
        _outputTxt.value = true
        _outputVtt.value = false
        _outputJson.value = false
        _result.value = null
        _error.value = null
    }
}

/**
 * State holder for PDFToolboxScreen
 * Persists PDF operations state across navigation
 */
object PDFStateHolder {
    private val _selectedFiles = MutableStateFlow<List<String>>(emptyList())
    val selectedFiles: StateFlow<List<String>> = _selectedFiles.asStateFlow()
    
    private val _operation = MutableStateFlow("")
    val operation: StateFlow<String> = _operation.asStateFlow()
    
    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()
    
    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()
    
    private val _result = MutableStateFlow<String?>(null)
    val result: StateFlow<String?> = _result.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    
    fun setSelectedFiles(files: List<String>) { _selectedFiles.value = files }
    fun addFile(path: String) { _selectedFiles.value = _selectedFiles.value + path }
    fun removeFile(path: String) { _selectedFiles.value = _selectedFiles.value.filter { it != path } }
    fun clearFiles() { _selectedFiles.value = emptyList() }
    fun setOperation(op: String) { _operation.value = op }
    fun setIsProcessing(processing: Boolean) { _isProcessing.value = processing }
    fun setProgress(p: Float) { _progress.value = p }
    fun setResult(path: String?) { _result.value = path }
    fun setError(msg: String?) { _error.value = msg }
    
    fun reset() {
        _selectedFiles.value = emptyList()
        _operation.value = ""
        _isProcessing.value = false
        _progress.value = 0f
        _result.value = null
        _error.value = null
    }
}

/**
 * State holder for VideoUpscalerScreen
 * Persists upscaling state across navigation
 */
object VideoUpscalerStateHolder {
    private val _inputPath = MutableStateFlow<String?>(null)
    val inputPath: StateFlow<String?> = _inputPath.asStateFlow()
    
    private val _inputUri = MutableStateFlow<Uri?>(null)
    val inputUri: StateFlow<Uri?> = _inputUri.asStateFlow()
    
    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()
    
    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()
    
    private val _currentFrame = MutableStateFlow(0)
    val currentFrame: StateFlow<Int> = _currentFrame.asStateFlow()
    
    private val _totalFrames = MutableStateFlow(0)
    val totalFrames: StateFlow<Int> = _totalFrames.asStateFlow()
    
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()
    
    private val _resultPath = MutableStateFlow<String?>(null)
    val resultPath: StateFlow<String?> = _resultPath.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    
    fun setInputPath(path: String?) { _inputPath.value = path }
    fun setInputUri(uri: Uri?) { _inputUri.value = uri }
    fun setIsProcessing(processing: Boolean) { _isProcessing.value = processing }
    fun setProgress(p: Float) { _progress.value = p }
    fun setCurrentFrame(frame: Int) { _currentFrame.value = frame }
    fun setTotalFrames(total: Int) { _totalFrames.value = total }
    fun setStatus(s: String) { _status.value = s }
    fun setResultPath(path: String?) { _resultPath.value = path }
    fun setError(msg: String?) { _error.value = msg }
    
    fun reset() {
        _inputPath.value = null
        _inputUri.value = null
        _isProcessing.value = false
        _progress.value = 0f
        _currentFrame.value = 0
        _totalFrames.value = 0
        _status.value = ""
        _resultPath.value = null
        _error.value = null
    }
}
