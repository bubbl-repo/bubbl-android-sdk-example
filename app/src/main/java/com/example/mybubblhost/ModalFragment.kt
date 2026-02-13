package com.example.mybubblhost

import android.animation.ObjectAnimator
import android.animation.AnimatorSet
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import tech.bubbl.sdk.BubblSdk
import tech.bubbl.sdk.notifications.NotificationRouter
import tech.bubbl.sdk.utils.Logger
import tech.bubbl.sdk.models.QuestionType
import tech.bubbl.sdk.models.SurveyAnswer
import tech.bubbl.sdk.models.ChoiceSelection
import kotlin.apply
import kotlin.jvm.java
import kotlin.let
import kotlin.text.contains
import kotlin.text.ifBlank
import kotlin.text.isNullOrBlank
import kotlin.text.lowercase
import kotlin.text.replace
import kotlin.text.trimIndent
import kotlin.to
class ModalFragment : DialogFragment() {

    companion object {
        private const val ARG_NOTIFICATION = "notification_json"

        fun newInstance(notification: NotificationRouter.DomainNotification): ModalFragment {
            return ModalFragment().apply {
                arguments = bundleOf(ARG_NOTIFICATION to Gson().toJson(notification))
            }
        }
    }

    private var exoPlayer: ExoPlayer? = null
    private var submitButton: Button? = null
    private var isSubmitting = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        Logger.log("ModalFragment", "🎭 onCreateDialog called!")

        val json = requireArguments().getString(ARG_NOTIFICATION)
            ?: throw kotlin.IllegalStateException("No notification data provided")

        Logger.log("ModalFragment", "📦 Got notification JSON: ${json.take(100)}...")

        val notification = Gson().fromJson(
            json,
            NotificationRouter.DomainNotification::class.java
        )

        Logger.log("ModalFragment", "✅ Parsed notification id=${notification.id}, headline='${notification.headline}'")

        // Inflate custom view
        val view = requireActivity().layoutInflater.inflate(
            R.layout.dialog_notification,
            null,
            false
        )

        setupView(view, notification)

        // Track notification delivered when modal is shown
        val isSurvey = notification.mediaType.isNullOrBlank() || notification.mediaType?.lowercase() == "survey"
        if (isSurvey) {
            BubblSdk.trackSurveyEvent(
                notificationId = notification.id.toString(),
                locationId = notification.locationId,
                activity = "notification_delivered"
            ) { success ->
                Log.d("ModalFragment", "Survey notification_delivered tracked: $success")
            }
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setView(view)
            .setPositiveButton("Close") { _, _ ->
                trackDismissal(notification)
                cleanupPlayer()
            }
            .setOnCancelListener {
                trackDismissal(notification)
                cleanupPlayer()
            }
            .create()
    }

    private fun setupView(view: View, notification: NotificationRouter.DomainNotification) {
        // Set text content
        view.findViewById<TextView>(R.id.tv_headline).text = notification.headline
        view.findViewById<TextView>(R.id.tv_body).text = notification.body.ifBlank { "(no body)" }

        // Handle media based on type
        val imageView = view.findViewById<ImageView>(R.id.iv_media)
        val videoContainer = view.findViewById<FrameLayout>(R.id.video_container)
        val audioContainer = view.findViewById<FrameLayout>(R.id.audio_container)
        val surveyContainer = view.findViewById<LinearLayout>(R.id.survey_container)

        // Check if this is a survey notification (no media or "survey" type)
        val mediaType = notification.mediaType
        val isSurvey = mediaType.isNullOrBlank() || mediaType?.lowercase() == "survey"

        // Track CTA engagement (but not for surveys - surveys use trackSurveyEvent)
        if (!isSurvey) {
            BubblSdk.cta(notification.id, notification.locationId)
            Logger.log("ModalFragment", "CTA tracked: id=${notification.id}, locationId=${notification.locationId}")
        }

        when {
            isSurvey -> {
                // Show survey UI
                surveyContainer.visibility = View.VISIBLE
                setupSurveyView(view, notification)
            }

            notification.mediaType?.lowercase() == "image" -> {
                imageView.visibility = View.VISIBLE
                notification.mediaUrl?.let { url ->
                    Glide.with(imageView)
                        .load(url)
                        .into(imageView)
                }
            }

            notification.mediaType?.lowercase() == "video" -> {
                videoContainer.visibility = View.VISIBLE
                notification.mediaUrl?.let { url ->
                    setupVideoPlayer(videoContainer, url)
                }
            }

            notification.mediaType?.lowercase() == "audio" -> {
                audioContainer.visibility = View.VISIBLE
                notification.mediaUrl?.let { url ->
                    setupAudioPlayer(audioContainer, url)
                }
            }

            else -> {
                // Text only - no media
                Logger.log("ModalFragment", "Text-only notification")
            }
        }

        // Setup CTA button
        val ctaButton = view.findViewById<Button>(R.id.btn_cta)
        val ctaUrl = notification.ctaUrl
        if (!notification.ctaLabel.isNullOrBlank() && !ctaUrl.isNullOrBlank()) {
            ctaButton.text = notification.ctaLabel
            ctaButton.visibility = View.VISIBLE
            ctaButton.setOnClickListener {
                openUrl(ctaUrl)
                dismiss()
            }
        } else {
            ctaButton.visibility = View.GONE
        }
    }

    private fun setupSurveyView(view: View, notification: NotificationRouter.DomainNotification) {
        val questionsContainer = view.findViewById<LinearLayout>(R.id.survey_questions_container)
        val submitBtn = view.findViewById<Button>(R.id.btn_survey_submit)

        // Store submit button reference
        submitButton = submitBtn

        // Initially disable submit button (will be enabled when form is complete)
        setSubmitButtonEnabled(false)

        // Store question views for later retrieval
        val questionViews = mutableMapOf<Int, View>()

        Log.d("ModalFragment", "Setting up survey with ${notification.questions?.size ?: 0} questions")
        Log.d("ModalFragment", "Post-survey message: '${notification.postMessage}'")

        // Parse and render each question with fade-in animation
        notification.questions?.sortedBy { it.position }?.forEachIndexed { index, question ->
            Log.d("ModalFragment", "Creating question view: id=${question.id}, type=${question.question_type}, position=${question.position}, has_choices=${question.has_choices}, choices_count=${question.choices?.size ?: 0}")
            val questionView = createQuestionView(question)
            questionView?.let {
                questionsContainer.addView(it)
                questionViews[question.id] = it
                // Animate fade-in with staggered delay
                animateFadeIn(it, delay = (index * 100L))
                Log.d("ModalFragment", "Question view added to container for question ${question.id}")
            } ?: run {
                Log.w("ModalFragment", "Question view was null for question ${question.id}")
            }
        }

        // Animate submit button fade-in
        submitBtn.alpha = 0f
        submitBtn.postDelayed({
            animateFadeIn(submitBtn, delay = (notification.questions?.size ?: 0) * 100L)
        }, 100)

        Log.d("ModalFragment", "Survey setup complete. Questions container has ${questionsContainer.childCount} children")

        // Track CTA engagement when survey is opened
        BubblSdk.trackSurveyEvent(
            notificationId = notification.id.toString(),
            locationId = notification.locationId,
            activity = "cta_engagement"
        ) { success ->
            Log.d("ModalFragment", "Survey cta_engagement tracked: $success")
        }

        submitBtn.setOnClickListener {
            // Prevent double submission
            if (isSubmitting) return@setOnClickListener

            // Hide keyboard
            hideKeyboard(it)

            // Collect all answers
            val answers = mutableListOf<SurveyAnswer>()
            val unansweredQuestions = mutableListOf<String>()

            notification.questions?.forEach { question ->
                val questionView = questionViews[question.id]
                val answer = collectAnswer(question, questionView)
                if (answer != null) {
                    answers.add(answer)
                } else {
                    unansweredQuestions.add("Q${question.position}. ${question.question}")
                }
            }

            Log.d("ModalFragment", "Survey submitted: ${answers.size} answers, notifId=${notification.id}, locationId=${notification.locationId}")

            // Validate: Check if all questions are answered
            if (unansweredQuestions.isNotEmpty()) {
                val errorMessage = if (unansweredQuestions.size == 1) {
                    "Please answer the following question:\n• ${unansweredQuestions[0]}"
                } else {
                    "Please answer all questions:\n${unansweredQuestions.joinToString("\n") { "• $it" }}"
                }

                android.app.AlertDialog.Builder(requireContext())
                    .setTitle("Incomplete Survey")
                    .setMessage(errorMessage)
                    .setPositiveButton("OK", null)
                    .show()

                Log.w("ModalFragment", "Survey incomplete: ${unansweredQuestions.size} unanswered questions")
                return@setOnClickListener
            }

            // Validate answers match expected formats
            val validationErrors = validateAnswers(answers)
            if (validationErrors.isNotEmpty()) {
                android.app.AlertDialog.Builder(requireContext())
                    .setTitle("Invalid Answers")
                    .setMessage("Please check your answers:\n${validationErrors.joinToString("\n") { "• $it" }}")
                    .setPositiveButton("OK", null)
                    .show()

                Log.w("ModalFragment", "Survey validation failed: $validationErrors")
                return@setOnClickListener
            }

            // Show loading state
            setSubmitButtonLoading(true)

            // Submit survey response via BubblSdk
            BubblSdk.submitSurveyResponse(
                notificationId = notification.id.toString(),
                locationId = notification.locationId,
                answers = answers
            ) { success ->
                // Use context instead of requireContext() since the fragment might be detached
                context?.let { ctx ->
                    if (success) {
                        Log.d("ModalFragment", "Survey submission successful")
                        // Use the post-survey message from the payload, or a default message
                        val message = notification.postMessage?.ifBlank { null }
                            ?: "Thank you for your response"

                        // Show success animation
                        submitButton?.let { btn ->
                            showSuccessAnimation(btn, "Submitted!")
                        }

                        Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()

                        // Dismiss after showing success
                        view?.postDelayed({
                            dismiss()
                        }, 1500)
                    } else {
                        Log.e("ModalFragment", "Survey submission failed")
                        Toast.makeText(ctx, "Failed to submit survey. Please try again.", Toast.LENGTH_LONG).show()
                        // Reset button state on failure
                        setSubmitButtonLoading(false)
                    }
                } ?: run {
                    // Fragment already detached, just log
                    if (success) {
                        Log.d("ModalFragment", "Survey submission successful (fragment detached)")
                    } else {
                        Log.e("ModalFragment", "Survey submission failed (fragment detached)")
                    }
                }
            }
        }
    }

    private fun createQuestionView(question: tech.bubbl.sdk.models.SurveyQuestion): View? {
        // Skip if null type
        if (question.question_type == null) {
            Log.w("ModalFragment", "Skipping question with null type: ${question.id}")
            return null
        }

        // Inflate appropriate layout based on question type
        val layoutId = when (question.question_type) {
            QuestionType.BOOLEAN -> R.layout.survey_question_boolean
            QuestionType.NUMBER -> R.layout.survey_question_number
            QuestionType.OPEN_ENDED -> R.layout.survey_question_open_ended
            QuestionType.RATING -> R.layout.survey_question_rating
            QuestionType.SLIDER -> R.layout.survey_question_slider
            QuestionType.SINGLE_CHOICE -> R.layout.survey_question_single_choice
            QuestionType.MULTIPLE_CHOICE -> R.layout.survey_question_multiple_choice
            null -> return null
        }

        val view = requireActivity().layoutInflater.inflate(layoutId, null, false)

        // Set question number and text
        view.findViewById<TextView>(R.id.tv_question_number)?.text = "Q${question.position}."
        view.findViewById<TextView>(R.id.tv_question_text)?.text = question.question

        // Setup specific input type
        when (question.question_type) {
            QuestionType.RATING -> setupRatingInput(view, question)
            QuestionType.SLIDER -> setupSliderInput(view, question)
            QuestionType.SINGLE_CHOICE -> setupSingleChoiceInput(view, question)
            QuestionType.MULTIPLE_CHOICE -> setupMultipleChoiceInput(view, question)
            QuestionType.OPEN_ENDED -> setupTextInput(view, R.id.et_answer)
            QuestionType.NUMBER -> setupTextInput(view, R.id.et_number)
            else -> {} // Other types are already configured in the layout
        }

        view.tag = question.id
        return view
    }

    private fun validateAnswers(answers: List<SurveyAnswer>): List<String> {
        val errors = mutableListOf<String>()

        answers.forEach { answer ->
            val questionType = try {
                QuestionType.valueOf(answer.type)
            } catch (e: IllegalArgumentException) {
                errors.add("Unknown question type: ${answer.type}")
                return@forEach
            }

            when (questionType) {
                QuestionType.MULTIPLE_CHOICE, QuestionType.SINGLE_CHOICE, QuestionType.SLIDER -> {
                    // Must have at least one choice selected
                    val choices = answer.choice
                    if (choices == null || choices.isEmpty()) {
                        errors.add("Please select at least one option for question ${answer.question_id}")
                    }
                }

                QuestionType.NUMBER -> {
                    // Must be a valid number
                    if (answer.value.toIntOrNull() == null) {
                        errors.add("Please enter a valid number for question ${answer.question_id}")
                    }
                }

                QuestionType.RATING -> {
                    // Must be between 1 and 5
                    val rating = answer.value.toIntOrNull()
                    if (rating == null || rating < 1 || rating > 5) {
                        errors.add("Rating must be between 1 and 5 stars for question ${answer.question_id}")
                    }
                }

                QuestionType.BOOLEAN -> {
                    // Must be true, false, yes, or no
                    val value = answer.value.lowercase()
                    if (value !in listOf("true", "false", "yes", "no")) {
                        errors.add("Invalid yes/no answer for question ${answer.question_id}")
                    }
                }

                QuestionType.OPEN_ENDED -> {
                    // Must not be blank
                    if (answer.value.trim().isEmpty()) {
                        errors.add("Please provide an answer for question ${answer.question_id}")
                    }
                }
            }
        }

        return errors
    }

    private fun trackDismissal(notification: NotificationRouter.DomainNotification) {
        // Only track dismissal for surveys (not for regular notifications)
        val mediaType = notification.mediaType
        val isSurvey = mediaType.isNullOrBlank() || mediaType?.lowercase() == "survey"

        if (isSurvey) {
            BubblSdk.trackSurveyEvent(
                notificationId = notification.id.toString(),
                locationId = notification.locationId,
                activity = "dismissed"
            ) { success ->
                Log.d("ModalFragment", "Survey dismissal tracked: $success")
            }
        }
    }

    private fun setupRatingInput(view: View, question: tech.bubbl.sdk.models.SurveyQuestion) {
        val stars = listOf(
            view.findViewById<ImageView>(R.id.star_1),
            view.findViewById<ImageView>(R.id.star_2),
            view.findViewById<ImageView>(R.id.star_3),
            view.findViewById<ImageView>(R.id.star_4),
            view.findViewById<ImageView>(R.id.star_5)
        )

        // Set initial unfilled color for all stars
        updateStarRating(stars, 0)

        stars.forEachIndexed { index, star ->
            star?.setOnClickListener {
                val rating = index + 1
                // Store rating in view's tag
                view.setTag(R.id.rating_container, rating)
                updateStarRating(stars, rating)
                // Haptic feedback
                performHapticFeedback(50)
                // Enable submit button as user fills form
                setSubmitButtonEnabled(true)
                Log.d("ModalFragment", "Star rating selected: $rating")
            }
        }
    }

    private fun updateStarRating(stars: List<ImageView?>, rating: Int) {
        stars.forEachIndexed { index, star ->
            star?.let {
                if (index < rating) {
                    it.setImageResource(android.R.drawable.star_big_on)
                    it.setColorFilter(
                        resources.getColor(R.color.survey_rating_filled, null),
                        android.graphics.PorterDuff.Mode.SRC_IN
                    )
                } else {
                    it.setImageResource(android.R.drawable.star_big_off)
                    it.setColorFilter(
                        resources.getColor(R.color.survey_rating_unfilled, null),
                        android.graphics.PorterDuff.Mode.SRC_IN
                    )
                }
            }
        }
    }

    private fun setupTextInput(view: View, editTextId: Int) {
        val editText = view.findViewById<EditText>(editTextId) ?: return

        // Enable submit button when user types
        editText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (!s.isNullOrBlank()) {
                    setSubmitButtonEnabled(true)
                }
            }
        })

        // Handle IME action (keyboard "Done" button)
        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) {
                hideKeyboard(editText)
                true
            } else {
                false
            }
        }
    }

    private fun setupSliderInput(view: View, question: tech.bubbl.sdk.models.SurveyQuestion) {
        val seekBar = view.findViewById<SeekBar>(R.id.seekbar)
        val valueText = view.findViewById<TextView>(R.id.tv_slider_value)
        val minLabel = view.findViewById<TextView>(R.id.tv_slider_min_label)
        val maxLabel = view.findViewById<TextView>(R.id.tv_slider_max_label)

        val choices = question.choices?.sortedBy { it.position } ?: emptyList()
        if (choices.isNotEmpty()) {
            // If only one choice, make slider 0-0 range and show the choice name
            val maxValue = if (choices.size == 1) 0 else choices.size - 1
            seekBar.max = maxValue

            // Show choice labels or fallback to Min/Max
            if (choices.size == 1) {
                minLabel.text = ""
                maxLabel.text = ""
                valueText.text = choices[0].choice
            } else {
                minLabel.text = choices.firstOrNull()?.choice ?: "Min"
                maxLabel.text = choices.lastOrNull()?.choice ?: "Max"
                valueText.text = choices.firstOrNull()?.choice ?: "0"
            }

            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    valueText.text = choices.getOrNull(progress)?.choice ?: progress.toString()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        } else {
            seekBar.max = 10
            minLabel.text = "0"
            maxLabel.text = "10"
            valueText.text = "0"

            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    valueText.text = progress.toString()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
    }

    private fun setupSingleChoiceInput(view: View, question: tech.bubbl.sdk.models.SurveyQuestion) {
        val radioGroup = view.findViewById<RadioGroup>(R.id.radio_group)
        question.choices?.sortedBy { it.position }?.forEach { choice ->
            val radioButton = RadioButton(requireContext()).apply {
                text = choice.choice
                id = View.generateViewId()
                tag = choice.id
                setPadding(
                    resources.getDimensionPixelSize(R.dimen.survey_choice_padding),
                    resources.getDimensionPixelSize(R.dimen.survey_choice_padding),
                    resources.getDimensionPixelSize(R.dimen.survey_choice_padding),
                    resources.getDimensionPixelSize(R.dimen.survey_choice_padding)
                )
                setTextColor(resources.getColor(R.color.survey_text_primary, null))
                textSize = 15f
                buttonTintList = android.content.res.ColorStateList.valueOf(
                    resources.getColor(R.color.survey_primary, null)
                )
                setBackgroundResource(R.drawable.survey_choice_background)
                layoutParams = RadioGroup.LayoutParams(
                    RadioGroup.LayoutParams.MATCH_PARENT,
                    RadioGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = resources.getDimensionPixelSize(R.dimen.survey_choice_margin)
                }
            }
            radioGroup.addView(radioButton)
        }
    }

    private fun setupMultipleChoiceInput(view: View, question: tech.bubbl.sdk.models.SurveyQuestion) {
        val container = view.findViewById<LinearLayout>(R.id.choices_container)
        Log.d("ModalFragment", "Setting up MULTIPLE_CHOICE for question ${question.id}, choices count: ${question.choices?.size ?: 0}")

        question.choices?.sortedBy { it.position }?.forEach { choice ->
            Log.d("ModalFragment", "Adding choice: id=${choice.id}, text=${choice.choice}, position=${choice.position}")
            val checkBox = CheckBox(requireContext()).apply {
                text = choice.choice
                tag = choice.id
                setPadding(
                    resources.getDimensionPixelSize(R.dimen.survey_choice_padding),
                    resources.getDimensionPixelSize(R.dimen.survey_choice_padding),
                    resources.getDimensionPixelSize(R.dimen.survey_choice_padding),
                    resources.getDimensionPixelSize(R.dimen.survey_choice_padding)
                )
                setTextColor(resources.getColor(R.color.survey_text_primary, null))
                textSize = 15f
                buttonTintList = android.content.res.ColorStateList.valueOf(
                    resources.getColor(R.color.survey_primary, null)
                )
                setBackgroundResource(R.drawable.survey_choice_background)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = resources.getDimensionPixelSize(R.dimen.survey_choice_margin)
                }
            }
            container.addView(checkBox)
        }

        Log.d("ModalFragment", "MULTIPLE_CHOICE setup complete, container has ${container.childCount} children")
    }

    private fun collectAnswer(question: tech.bubbl.sdk.models.SurveyQuestion, questionView: View?): SurveyAnswer? {
        if (questionView == null) return null
        if (question.question_type == null) return null

        val questionId = question.id

        return when (question.question_type) {
            QuestionType.BOOLEAN -> {
                val radioGroup = questionView.findViewById<RadioGroup>(R.id.radio_group)
                val checkedId = radioGroup?.checkedRadioButtonId ?: -1
                if (checkedId == -1) return null
                val selectedButton = radioGroup?.findViewById<RadioButton>(checkedId)
                val value = if (selectedButton?.tag == "yes") "true" else "false"
                SurveyAnswer(
                    question_id = questionId,
                    type = "BOOLEAN",
                    value = value
                )
            }

            QuestionType.NUMBER -> {
                val editText = questionView.findViewById<EditText>(R.id.et_number)
                val value = editText?.text?.toString() ?: ""
                if (value.isBlank()) return null
                SurveyAnswer(
                    question_id = questionId,
                    type = "NUMBER",
                    value = value
                )
            }

            QuestionType.OPEN_ENDED -> {
                val editText = questionView.findViewById<EditText>(R.id.et_answer)
                val value = editText?.text?.toString() ?: ""
                if (value.isBlank()) return null
                SurveyAnswer(
                    question_id = questionId,
                    type = "OPEN_ENDED",
                    value = value
                )
            }

            QuestionType.RATING -> {
                val rating = (questionView.getTag(R.id.rating_container) as? Int) ?: 0
                if (rating == 0) return null
                SurveyAnswer(
                    question_id = questionId,
                    type = "RATING",
                    value = rating.toString()
                )
            }

            QuestionType.SLIDER -> {
                val seekBar = questionView.findViewById<SeekBar>(R.id.seekbar)
                val progress = seekBar?.progress ?: 0
                val choiceId = question.choices?.sortedBy { it.position }?.getOrNull(progress)?.id
                if (choiceId == null) {
                    Log.e("ModalFragment", "No choice found at index $progress for SLIDER question")
                    return null
                }
                SurveyAnswer(
                    question_id = questionId,
                    type = "SLIDER",
                    value = progress.toString(),
                    choice = listOf(ChoiceSelection(choice_id = choiceId))
                )
            }

            QuestionType.SINGLE_CHOICE -> {
                val radioGroup = questionView.findViewById<RadioGroup>(R.id.radio_group)
                val checkedId = radioGroup?.checkedRadioButtonId ?: -1
                if (checkedId == -1) return null
                val selectedButton = radioGroup?.findViewById<RadioButton>(checkedId)
                val choiceId = selectedButton?.tag as? Int
                if (choiceId == null) {
                    Log.e("ModalFragment", "Invalid choice ID for SINGLE_CHOICE question")
                    return null
                }
                SurveyAnswer(
                    question_id = questionId,
                    type = "SINGLE_CHOICE",
                    value = selectedButton.text.toString(),
                    choice = listOf(ChoiceSelection(choice_id = choiceId))
                )
            }

            QuestionType.MULTIPLE_CHOICE -> {
                val container = questionView.findViewById<LinearLayout>(R.id.choices_container)
                val selectedChoices = mutableListOf<ChoiceSelection>()
                if (container != null) {
                    for (i in 0 until container.childCount) {
                        val checkBox = container.getChildAt(i) as? CheckBox
                        if (checkBox?.isChecked == true) {
                            val choiceId = checkBox.tag as? Int
                            if (choiceId != null) {
                                selectedChoices.add(ChoiceSelection(choice_id = choiceId))
                            } else {
                                Log.e("ModalFragment", "Invalid choice ID in MULTIPLE_CHOICE, skipping")
                            }
                        }
                    }
                }
                if (selectedChoices.isEmpty()) return null
                SurveyAnswer(
                    question_id = questionId,
                    type = "MULTIPLE_CHOICE",
                    value = "YES",
                    choice = selectedChoices
                )
            }
            null -> null
        }
    }

    private fun setupVideoPlayer(container: FrameLayout, url: String) {
        // For YouTube URLs, use youtube-nocookie.com embed (privacy-enhanced, no consent screen)
        if (url.contains("youtube.com", ignoreCase = true) || url.contains("youtu.be", ignoreCase = true)) {
            // Extract video ID
            val videoId = if (url.contains("/embed/")) {
                url.substringAfter("/embed/").substringBefore("?")
            } else {
                url.substringAfter("v=").substringBefore("&")
            }

            val webView = android.webkit.WebView(requireContext()).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                }

                webViewClient = android.webkit.WebViewClient()

                // Enable fullscreen support for video player
                webChromeClient = object : android.webkit.WebChromeClient() {
                    private var customView: View? = null
                    private var customViewCallback: CustomViewCallback? = null
                    private var originalSystemUiVisibility: Int = 0

                    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                        if (customView != null) {
                            callback?.onCustomViewHidden()
                            return
                        }

                        customView = view
                        customViewCallback = callback

                        // Hide the WebView and show fullscreen video view
                        this@apply.visibility = View.GONE
                        container.addView(
                            view,
                            android.widget.FrameLayout.LayoutParams(
                                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                            )
                        )

                        // Hide system UI for true fullscreen
                        originalSystemUiVisibility = requireActivity().window.decorView.systemUiVisibility
                        requireActivity().window.decorView.systemUiVisibility = (
                                View.SYSTEM_UI_FLAG_FULLSCREEN
                                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                )

                        Log.d("ModalFragment", "YouTube fullscreen enabled")
                    }

                    override fun onHideCustomView() {
                        if (customView == null) return

                        // Remove fullscreen view and restore WebView
                        container.removeView(customView)
                        this@apply.visibility = View.VISIBLE
                        customView = null
                        customViewCallback?.onCustomViewHidden()
                        customViewCallback = null

                        // Restore system UI
                        requireActivity().window.decorView.systemUiVisibility = originalSystemUiVisibility

                        Log.d("ModalFragment", "YouTube fullscreen disabled")
                    }
                }

                // Use youtube-nocookie.com - privacy-enhanced mode without consent
                val embedHtml = """
                    <!DOCTYPE html>
                    <html style="height: 100%;">
                    <head>
                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                        <style>
                            * { margin: 0; padding: 0; box-sizing: border-box; }
                            html, body {
                                width: 100%;
                                height: 100%;
                                overflow: hidden;
                                background: #000;
                            }
                            iframe {
                                position: absolute;
                                top: 0;
                                left: 0;
                                width: 100% !important;
                                height: 100% !important;
                                border: none;
                            }
                        </style>
                    </head>
                    <body style="height: 100%;">
                        <iframe src="https://www.youtube-nocookie.com/embed/$videoId"
                                frameborder="0"
                                allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture"
                                allowfullscreen
                                style="width: 100%; height: 100%;">
                        </iframe>
                    </body>
                    </html>
                """.trimIndent()

                loadDataWithBaseURL("https://www.youtube-nocookie.com", embedHtml, "text/html", "UTF-8", null)
                Log.d("ModalFragment", "Loading YouTube nocookie embed: $videoId")
            }

            container.addView(webView)
        } else{
            // Use ExoPlayer for other video URLs
            exoPlayer = ExoPlayer.Builder(requireContext()).build().apply {
                setMediaItem(MediaItem.fromUri(url))
                prepare()
                playWhenReady = false
            }

            val playerView = PlayerView(requireContext()).apply {
                player = exoPlayer
            }
            container.addView(playerView)
        }
    }

    private fun showYouTubeFallback(container: FrameLayout, url: String) {
        requireActivity().runOnUiThread {
            container.removeAllViews()

            val watchButton = Button(requireContext()).apply {
                text = "▶ Watch on YouTube"
                textSize = 16f
                setPadding(32, 32, 32, 32)
                setOnClickListener {
                    openUrl(url)
                }
            }

            val messageLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setPadding(48, 48, 48, 48)

                addView(TextView(requireContext()).apply {
                    text = "📹 YouTube Video"
                    textSize = 18f
                    gravity = android.view.Gravity.CENTER
                    setPadding(0, 0, 0, 24)
                })

                addView(watchButton)

                addView(TextView(requireContext()).apply {
                    text = "This video cannot be embedded. Tap to watch on YouTube."
                    textSize = 12f
                    setTextColor(android.graphics.Color.GRAY)
                    gravity = android.view.Gravity.CENTER
                    setPadding(0, 16, 0, 0)
                })
            }

            container.addView(messageLayout)
            Log.d("ModalFragment", "YouTube fallback button shown for URL: $url")
        }
    }

    private fun setupAudioPlayer(container: FrameLayout, url: String) {
        exoPlayer = ExoPlayer.Builder(requireContext()).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = false
        }

        val playerView = PlayerView(requireContext()).apply {
            player = exoPlayer
        }
        container.addView(playerView)
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
            Logger.log("ModalFragment", "Opened URL: $url")
        } catch (e: Exception) {
            Logger.log("ModalFragment", "Failed to open URL: ${e.message}")
        }
    }

    private fun cleanupPlayer() {
        exoPlayer?.release()
        exoPlayer = null
    }

    private fun performHapticFeedback(duration: Long = 50) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = requireContext().getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                requireContext().getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            vibrator?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    it.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    it.vibrate(duration)
                }
            }
        } catch (e: Exception) {
            Log.w("ModalFragment", "Haptic feedback failed: ${e.message}")
        }
    }

    private fun animateFadeIn(view: View, delay: Long = 0) {
        view.alpha = 0f
        view.animate()
            .alpha(1f)
            .setDuration(300)
            .setStartDelay(delay)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun showSuccessAnimation(button: Button, message: String) {
        // Scale up with pulse effect
        val scaleX = ObjectAnimator.ofFloat(button, "scaleX", 1f, 1.1f, 1f)
        val scaleY = ObjectAnimator.ofFloat(button, "scaleY", 1f, 1.1f, 1f)

        AnimatorSet().apply {
            playTogether(scaleX, scaleY)
            duration = 400
            start()
        }

        // Update button text briefly to show success
        val originalText = button.text
        button.text = "✓ $message"
        button.postDelayed({
            button.text = originalText
        }, 1500)

        // Haptic feedback for success
        performHapticFeedback(100)
    }

    private fun setSubmitButtonEnabled(enabled: Boolean) {
        submitButton?.let { button ->
            button.isEnabled = enabled
            button.alpha = if (enabled) 1f else 0.5f

            // Visual feedback
            if (enabled) {
                button.animate()
                    .alpha(1f)
                    .scaleX(1.02f)
                    .scaleY(1.02f)
                    .setDuration(200)
                    .withEndAction {
                        button.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(200)
                            .start()
                    }
                    .start()
            }
        }
    }

    private fun setSubmitButtonLoading(loading: Boolean) {
        submitButton?.let { button ->
            if (loading) {
                isSubmitting = true
                button.isEnabled = false
                button.text = "Submitting..."
                button.alpha = 0.7f
            } else {
                isSubmitting = false
                button.isEnabled = true
                button.text = "Submit Survey"
                button.alpha = 1f
            }
        }
    }

    private fun hideKeyboard(view: View) {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    override fun onDestroyView() {
        cleanupPlayer()
        super.onDestroyView()
    }
}

// V3
//import android.app.Dialog
//import android.content.Intent
//import android.net.Uri
//import android.os.Bundle
//import android.util.Log
//import android.view.View
//import android.widget.Button
//import android.widget.CheckBox
//import android.widget.EditText
//import android.widget.FrameLayout
//import android.widget.ImageView
//import android.widget.LinearLayout
//import android.widget.RadioButton
//import android.widget.RadioGroup
//import android.widget.RatingBar
//import android.widget.SeekBar
//import android.widget.TextView
//import android.widget.Toast
//import androidx.core.os.bundleOf
//import androidx.fragment.app.DialogFragment
//import androidx.media3.common.MediaItem
//import androidx.media3.exoplayer.ExoPlayer
//import androidx.media3.ui.PlayerView
//import com.bumptech.glide.Glide
//import com.google.android.material.dialog.MaterialAlertDialogBuilder
//import com.google.gson.Gson
//import tech.bubbl.sdk.BubblSdk
//import tech.bubbl.sdk.notifications.NotificationRouter
//import tech.bubbl.sdk.utils.Logger
//import tech.bubbl.sdk.models.QuestionType
//import tech.bubbl.sdk.models.SurveyAnswer
//import tech.bubbl.sdk.models.ChoiceSelection
//import kotlin.apply
//import kotlin.jvm.java
//import kotlin.let
//import kotlin.text.contains
//import kotlin.text.ifBlank
//import kotlin.text.isNullOrBlank
//import kotlin.text.lowercase
//import kotlin.text.replace
//import kotlin.text.trimIndent
//import kotlin.to
//
//class ModalFragment : DialogFragment() {
//
//    companion object {
//        private const val ARG_NOTIFICATION = "notification_json"
//
//        fun newInstance(notification: NotificationRouter.DomainNotification): ModalFragment {
//            return ModalFragment().apply {
//                arguments = bundleOf(ARG_NOTIFICATION to Gson().toJson(notification))
//            }
//        }
//    }
//
//    private var exoPlayer: ExoPlayer? = null
//
//    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
//        Logger.log("ModalFragment", "🎭 onCreateDialog called!")
//
//        val json = requireArguments().getString(ARG_NOTIFICATION)
//            ?: throw kotlin.IllegalStateException("No notification data provided")
//
//        Logger.log("ModalFragment", "📦 Got notification JSON: ${json.take(100)}...")
//
//        val notification = Gson().fromJson(
//            json,
//            NotificationRouter.DomainNotification::class.java
//        )
//
//        Logger.log("ModalFragment", "✅ Parsed notification id=${notification.id}, headline='${notification.headline}'")
//
//        // Inflate custom view
//        val view = requireActivity().layoutInflater.inflate(
//            R.layout.dialog_notification,
//            null,
//            false
//        )
//
//        setupView(view, notification)
//
//        // Track notification delivered when modal is shown
//        val isSurvey = notification.mediaType.isNullOrBlank() || notification.mediaType?.lowercase() == "survey"
//        if (isSurvey) {
//            BubblSdk.trackSurveyEvent(
//                notificationId = notification.id.toString(),
//                locationId = notification.locationId,
//                activity = "notification_delivered"
//            ) { success ->
//                Log.d("ModalFragment", "Survey notification_delivered tracked: $success")
//            }
//        }
//
//        return MaterialAlertDialogBuilder(requireContext())
//            .setView(view)
//            .setPositiveButton("Close") { _, _ ->
//                trackDismissal(notification)
//                cleanupPlayer()
//            }
//            .setOnCancelListener {
//                trackDismissal(notification)
//                cleanupPlayer()
//            }
//            .create()
//    }
//
//    private fun setupView(view: View, notification: NotificationRouter.DomainNotification) {
//        // Set text content
//        view.findViewById<TextView>(R.id.tv_headline).text = notification.headline
//        view.findViewById<TextView>(R.id.tv_body).text = notification.body.ifBlank { "(no body)" }
//
//        // Handle media based on type
//        val imageView = view.findViewById<ImageView>(R.id.iv_media)
//        val videoContainer = view.findViewById<FrameLayout>(R.id.video_container)
//        val audioContainer = view.findViewById<FrameLayout>(R.id.audio_container)
//        val surveyContainer = view.findViewById<LinearLayout>(R.id.survey_container)
//
//        // Check if this is a survey notification (no media or "survey" type)
//        val mediaType = notification.mediaType
//        val isSurvey = mediaType.isNullOrBlank() || mediaType?.lowercase() == "survey"
//
//        // Track CTA engagement (but not for surveys - surveys use trackSurveyEvent)
//        if (!isSurvey) {
//            BubblSdk.cta(notification.id, notification.locationId)
//            Logger.log("ModalFragment", "CTA tracked: id=${notification.id}, locationId=${notification.locationId}")
//        }
//
//        when {
//            isSurvey -> {
//                // Show survey UI
//                surveyContainer.visibility = View.VISIBLE
//                setupSurveyView(view, notification)
//            }
//
//            notification.mediaType?.lowercase() == "image" -> {
//                imageView.visibility = View.VISIBLE
//                notification.mediaUrl?.let { url ->
//                    Glide.with(imageView)
//                        .load(url)
//                        .into(imageView)
//                }
//            }
//
//            notification.mediaType?.lowercase() == "video" -> {
//                videoContainer.visibility = View.VISIBLE
//                notification.mediaUrl?.let { url ->
//                    setupVideoPlayer(videoContainer, url)
//                }
//            }
//
//            notification.mediaType?.lowercase() == "audio" -> {
//                audioContainer.visibility = View.VISIBLE
//                notification.mediaUrl?.let { url ->
//                    setupAudioPlayer(audioContainer, url)
//                }
//            }
//
//            else -> {
//                // Text only - no media
//                Logger.log("ModalFragment", "Text-only notification")
//            }
//        }
//
//        // Setup CTA button
//        val ctaButton = view.findViewById<Button>(R.id.btn_cta)
//        val ctaUrl = notification.ctaUrl
//        if (!notification.ctaLabel.isNullOrBlank() && !ctaUrl.isNullOrBlank()) {
//            ctaButton.text = notification.ctaLabel
//            ctaButton.visibility = View.VISIBLE
//            ctaButton.setOnClickListener {
//                openUrl(ctaUrl)
//                dismiss()
//            }
//        } else {
//            ctaButton.visibility = View.GONE
//        }
//    }
//
//    private fun setupSurveyView(view: View, notification: NotificationRouter.DomainNotification) {
//        val questionsContainer = view.findViewById<LinearLayout>(R.id.survey_questions_container)
//        val submitBtn = view.findViewById<Button>(R.id.btn_survey_submit)
//
//        // Store question views for later retrieval
//        val questionViews = mutableMapOf<Int, View>()
//
//        Log.d("ModalFragment", "Setting up survey with ${notification.questions?.size ?: 0} questions")
//        Log.d("ModalFragment", "Post-survey message: '${notification.postMessage}'")
//
//        // Parse and render each question
//        notification.questions?.sortedBy { it.position }?.forEach { question ->
//            Log.d("ModalFragment", "Creating question view: id=${question.id}, type=${question.question_type}, position=${question.position}, has_choices=${question.has_choices}, choices_count=${question.choices?.size ?: 0}")
//            val questionView = createQuestionView(question)
//            questionView?.let {
//                questionsContainer.addView(it)
//                questionViews[question.id] = it
//                Log.d("ModalFragment", "Question view added to container for question ${question.id}")
//            } ?: run {
//                Log.w("ModalFragment", "Question view was null for question ${question.id}")
//            }
//        }
//
//        Log.d("ModalFragment", "Survey setup complete. Questions container has ${questionsContainer.childCount} children")
//
//        // Track CTA engagement when survey is opened
//        BubblSdk.trackSurveyEvent(
//            notificationId = notification.id.toString(),
//            locationId = notification.locationId,
//            activity = "cta_engagement"
//        ) { success ->
//            Log.d("ModalFragment", "Survey cta_engagement tracked: $success")
//        }
//
//        submitBtn.setOnClickListener {
//            // Collect all answers
//            val answers = mutableListOf<SurveyAnswer>()
//
//            notification.questions?.forEach { question ->
//                val questionView = questionViews[question.id]
//                val answer = collectAnswer(question, questionView)
//                answer?.let { answers.add(it) }
//            }
//
//            Log.d("ModalFragment", "Survey submitted: ${answers.size} answers, notifId=${notification.id}, locationId=${notification.locationId}")
//
//            // Submit survey response via BubblSdk
//            BubblSdk.submitSurveyResponse(
//                notificationId = notification.id.toString(),
//                locationId = notification.locationId,
//                answers = answers
//            ) { success ->
//                // Use context instead of requireContext() since the fragment might be detached
//                context?.let { ctx ->
//                    if (success) {
//                        Log.d("ModalFragment", "Survey submission successful")
//                        // Use the post-survey message from the payload, or a default message
//                        val message = notification.postMessage?.ifBlank { null }
//                            ?: "Thank you for your feedback!"
//                        Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
//                    } else {
//                        Log.e("ModalFragment", "Survey submission failed")
//                        Toast.makeText(ctx, "Failed to submit survey. Please try again.", Toast.LENGTH_LONG).show()
//                    }
//                } ?: run {
//                    // Fragment already detached, just log
//                    if (success) {
//                        Log.d("ModalFragment", "Survey submission successful (fragment detached)")
//                    } else {
//                        Log.e("ModalFragment", "Survey submission failed (fragment detached)")
//                    }
//                }
//            }
//
//            // Dismiss after a short delay to allow the Toast to be shown
//            view?.postDelayed({
//                dismiss()
//            }, 500)
//        }
//    }
//
//    private fun createQuestionView(question: tech.bubbl.sdk.models.SurveyQuestion): View? {
//        // Skip if null type
//        if (question.question_type == null) {
//            Log.w("ModalFragment", "Skipping question with null type: ${question.id}")
//            return null
//        }
//
//        // Inflate appropriate layout based on question type
//        val layoutId = when (question.question_type) {
//            QuestionType.BOOLEAN -> R.layout.survey_question_boolean
//            QuestionType.NUMBER -> R.layout.survey_question_number
//            QuestionType.OPEN_ENDED -> R.layout.survey_question_open_ended
//            QuestionType.RATING -> R.layout.survey_question_rating
//            QuestionType.SLIDER -> R.layout.survey_question_slider
//            QuestionType.SINGLE_CHOICE -> R.layout.survey_question_single_choice
//            QuestionType.MULTIPLE_CHOICE -> R.layout.survey_question_multiple_choice
//            null -> return null
//        }
//
//        val view = requireActivity().layoutInflater.inflate(layoutId, null, false)
//
//        // Set question number and text
//        view.findViewById<TextView>(R.id.tv_question_number)?.text = "Q${question.position}."
//        view.findViewById<TextView>(R.id.tv_question_text)?.text = question.question
//
//        // Setup specific input type
//        when (question.question_type) {
//            QuestionType.RATING -> setupRatingInput(view, question)
//            QuestionType.SLIDER -> setupSliderInput(view, question)
//            QuestionType.SINGLE_CHOICE -> setupSingleChoiceInput(view, question)
//            QuestionType.MULTIPLE_CHOICE -> setupMultipleChoiceInput(view, question)
//            else -> {} // Other types are already configured in the layout
//        }
//
//        view.tag = question.id
//        return view
//    }
//
//    private fun trackDismissal(notification: NotificationRouter.DomainNotification) {
//        // Only track dismissal for surveys (not for regular notifications)
//        val mediaType = notification.mediaType
//        val isSurvey = mediaType.isNullOrBlank() || mediaType?.lowercase() == "survey"
//
//        if (isSurvey) {
//            BubblSdk.trackSurveyEvent(
//                notificationId = notification.id.toString(),
//                locationId = notification.locationId,
//                activity = "dismissed"
//            ) { success ->
//                Log.d("ModalFragment", "Survey dismissal tracked: $success")
//            }
//        }
//    }
//
//    private fun setupRatingInput(view: View, question: tech.bubbl.sdk.models.SurveyQuestion) {
//        val ratingBar = view.findViewById<RatingBar>(R.id.rating_bar)
//
//        // Fix the touch offset issue by using OnRatingBarChangeListener and custom touch handling
//        ratingBar?.setOnTouchListener { v, event ->
//            if (event.action == android.view.MotionEvent.ACTION_DOWN ||
//                event.action == android.view.MotionEvent.ACTION_MOVE) {
//                val bar = v as RatingBar
//                val width = bar.width - bar.paddingLeft - bar.paddingRight
//                val numStars = bar.numStars
//                val starWidth = width / numStars.toFloat()
//                val touchX = event.x - bar.paddingLeft
//
//                // Calculate the rating based on touch position
//                // Add 0.5f to round to nearest star (fixes the offset issue)
//                val rating = ((touchX / starWidth) + 0.5f).toInt().coerceIn(1, numStars)
//                bar.rating = rating.toFloat()
//
//                Log.d("ModalFragment", "Rating touched: x=${event.x}, calculated rating=$rating")
//                return@setOnTouchListener true
//            }
//            false
//        }
//    }
//
//    private fun setupSliderInput(view: View, question: tech.bubbl.sdk.models.SurveyQuestion) {
//        val seekBar = view.findViewById<SeekBar>(R.id.seekbar)
//        val valueText = view.findViewById<TextView>(R.id.tv_slider_value)
//        val minLabel = view.findViewById<TextView>(R.id.tv_slider_min_label)
//        val maxLabel = view.findViewById<TextView>(R.id.tv_slider_max_label)
//
//        val choices = question.choices?.sortedBy { it.position } ?: emptyList()
//        if (choices.isNotEmpty()) {
//            // If only one choice, make slider 0-0 range and show the choice name
//            val maxValue = if (choices.size == 1) 0 else choices.size - 1
//            seekBar.max = maxValue
//
//            // Show choice labels or fallback to Min/Max
//            if (choices.size == 1) {
//                minLabel.text = ""
//                maxLabel.text = ""
//                valueText.text = choices[0].choice
//            } else {
//                minLabel.text = choices.firstOrNull()?.choice ?: "Min"
//                maxLabel.text = choices.lastOrNull()?.choice ?: "Max"
//                valueText.text = choices.firstOrNull()?.choice ?: "0"
//            }
//
//            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
//                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
//                    valueText.text = choices.getOrNull(progress)?.choice ?: progress.toString()
//                }
//                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
//                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
//            })
//        } else {
//            seekBar.max = 10
//            minLabel.text = "0"
//            maxLabel.text = "10"
//            valueText.text = "0"
//
//            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
//                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
//                    valueText.text = progress.toString()
//                }
//                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
//                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
//            })
//        }
//    }
//
//    private fun setupSingleChoiceInput(view: View, question: tech.bubbl.sdk.models.SurveyQuestion) {
//        val radioGroup = view.findViewById<RadioGroup>(R.id.radio_group)
//        question.choices?.sortedBy { it.position }?.forEach { choice ->
//            val radioButton = RadioButton(requireContext()).apply {
//                text = choice.choice
//                id = View.generateViewId()
//                tag = choice.id
//                setPadding(
//                    resources.getDimensionPixelSize(R.dimen.survey_choice_padding),
//                    resources.getDimensionPixelSize(R.dimen.survey_choice_padding),
//                    resources.getDimensionPixelSize(R.dimen.survey_choice_padding),
//                    resources.getDimensionPixelSize(R.dimen.survey_choice_padding)
//                )
//                setTextColor(resources.getColor(R.color.survey_text_primary, null))
//                textSize = 15f
//                buttonTintList = android.content.res.ColorStateList.valueOf(
//                    resources.getColor(R.color.survey_primary, null)
//                )
//                setBackgroundResource(R.drawable.survey_choice_background)
//                layoutParams = RadioGroup.LayoutParams(
//                    RadioGroup.LayoutParams.MATCH_PARENT,
//                    RadioGroup.LayoutParams.WRAP_CONTENT
//                ).apply {
//                    bottomMargin = resources.getDimensionPixelSize(R.dimen.survey_choice_margin)
//                }
//            }
//            radioGroup.addView(radioButton)
//        }
//    }
//
//    private fun setupMultipleChoiceInput(view: View, question: tech.bubbl.sdk.models.SurveyQuestion) {
//        val container = view.findViewById<LinearLayout>(R.id.choices_container)
//        Log.d("ModalFragment", "Setting up MULTIPLE_CHOICE for question ${question.id}, choices count: ${question.choices?.size ?: 0}")
//
//        question.choices?.sortedBy { it.position }?.forEach { choice ->
//            Log.d("ModalFragment", "Adding choice: id=${choice.id}, text=${choice.choice}, position=${choice.position}")
//            val checkBox = CheckBox(requireContext()).apply {
//                text = choice.choice
//                tag = choice.id
//                setPadding(
//                    resources.getDimensionPixelSize(R.dimen.survey_choice_padding),
//                    resources.getDimensionPixelSize(R.dimen.survey_choice_padding),
//                    resources.getDimensionPixelSize(R.dimen.survey_choice_padding),
//                    resources.getDimensionPixelSize(R.dimen.survey_choice_padding)
//                )
//                setTextColor(resources.getColor(R.color.survey_text_primary, null))
//                textSize = 15f
//                buttonTintList = android.content.res.ColorStateList.valueOf(
//                    resources.getColor(R.color.survey_primary, null)
//                )
//                setBackgroundResource(R.drawable.survey_choice_background)
//                layoutParams = LinearLayout.LayoutParams(
//                    LinearLayout.LayoutParams.MATCH_PARENT,
//                    LinearLayout.LayoutParams.WRAP_CONTENT
//                ).apply {
//                    bottomMargin = resources.getDimensionPixelSize(R.dimen.survey_choice_margin)
//                }
//            }
//            container.addView(checkBox)
//        }
//
//        Log.d("ModalFragment", "MULTIPLE_CHOICE setup complete, container has ${container.childCount} children")
//    }
//
//    private fun collectAnswer(question: tech.bubbl.sdk.models.SurveyQuestion, questionView: View?): SurveyAnswer? {
//        if (questionView == null) return null
//        if (question.question_type == null) return null
//
//        val questionId = question.id
//
//        return when (question.question_type) {
//            QuestionType.BOOLEAN -> {
//                val radioGroup = questionView.findViewById<RadioGroup>(R.id.radio_group)
//                val checkedId = radioGroup?.checkedRadioButtonId ?: -1
//                if (checkedId == -1) return null
//                val selectedButton = radioGroup?.findViewById<RadioButton>(checkedId)
//                val value = if (selectedButton?.tag == "yes") "true" else "false"
//                SurveyAnswer(
//                    question_id = questionId,
//                    type = "BOOLEAN",
//                    value = value
//                )
//            }
//
//            QuestionType.NUMBER -> {
//                val editText = questionView.findViewById<EditText>(R.id.et_number)
//                val value = editText?.text?.toString() ?: ""
//                if (value.isBlank()) return null
//                SurveyAnswer(
//                    question_id = questionId,
//                    type = "NUMBER",
//                    value = value
//                )
//            }
//
//            QuestionType.OPEN_ENDED -> {
//                val editText = questionView.findViewById<EditText>(R.id.et_answer)
//                val value = editText?.text?.toString() ?: ""
//                if (value.isBlank()) return null
//                SurveyAnswer(
//                    question_id = questionId,
//                    type = "OPEN_ENDED",
//                    value = value
//                )
//            }
//
//            QuestionType.RATING -> {
//                val ratingBar = questionView.findViewById<RatingBar>(R.id.rating_bar)
//                val rating = ratingBar?.rating?.toInt() ?: 0
//                if (rating == 0) return null
//                SurveyAnswer(
//                    question_id = questionId,
//                    type = "RATING",
//                    value = rating.toString()
//                )
//            }
//
//            QuestionType.SLIDER -> {
//                val seekBar = questionView.findViewById<SeekBar>(R.id.seekbar)
//                val progress = seekBar?.progress ?: 0
//                val choiceId = question.choices?.sortedBy { it.position }?.getOrNull(progress)?.id
//                if (choiceId == null) {
//                    Log.e("ModalFragment", "No choice found at index $progress for SLIDER question")
//                    return null
//                }
//                SurveyAnswer(
//                    question_id = questionId,
//                    type = "SLIDER",
//                    value = progress.toString(),
//                    choice = listOf(ChoiceSelection(choice_id = choiceId))
//                )
//            }
//
//            QuestionType.SINGLE_CHOICE -> {
//                val radioGroup = questionView.findViewById<RadioGroup>(R.id.radio_group)
//                val checkedId = radioGroup?.checkedRadioButtonId ?: -1
//                if (checkedId == -1) return null
//                val selectedButton = radioGroup?.findViewById<RadioButton>(checkedId)
//                val choiceId = selectedButton?.tag as? Int
//                if (choiceId == null) {
//                    Log.e("ModalFragment", "Invalid choice ID for SINGLE_CHOICE question")
//                    return null
//                }
//                SurveyAnswer(
//                    question_id = questionId,
//                    type = "SINGLE_CHOICE",
//                    value = selectedButton.text.toString(),
//                    choice = listOf(ChoiceSelection(choice_id = choiceId))
//                )
//            }
//
//            QuestionType.MULTIPLE_CHOICE -> {
//                val container = questionView.findViewById<LinearLayout>(R.id.choices_container)
//                val selectedChoices = mutableListOf<ChoiceSelection>()
//                if (container != null) {
//                    for (i in 0 until container.childCount) {
//                        val checkBox = container.getChildAt(i) as? CheckBox
//                        if (checkBox?.isChecked == true) {
//                            val choiceId = checkBox.tag as? Int
//                            if (choiceId != null) {
//                                selectedChoices.add(ChoiceSelection(choice_id = choiceId))
//                            } else {
//                                Log.e("ModalFragment", "Invalid choice ID in MULTIPLE_CHOICE, skipping")
//                            }
//                        }
//                    }
//                }
//                if (selectedChoices.isEmpty()) return null
//                SurveyAnswer(
//                    question_id = questionId,
//                    type = "MULTIPLE_CHOICE",
//                    value = "YES",
//                    choice = selectedChoices
//                )
//            }
//            null -> null
//        }
//    }
//
//    private fun setupVideoPlayer(container: FrameLayout, url: String) {
//        // For YouTube URLs, use youtube-nocookie.com embed (privacy-enhanced, no consent screen)
//        if (url.contains("youtube.com", ignoreCase = true) || url.contains("youtu.be", ignoreCase = true)) {
//            // Extract video ID
//            val videoId = if (url.contains("/embed/")) {
//                url.substringAfter("/embed/").substringBefore("?")
//            } else {
//                url.substringAfter("v=").substringBefore("&")
//            }
//
//            val webView = android.webkit.WebView(requireContext()).apply {
//                settings.apply {
//                    javaScriptEnabled = true
//                    domStorageEnabled = true
//                    mediaPlaybackRequiresUserGesture = false
//                }
//
//                webViewClient = android.webkit.WebViewClient()
//
//                // Enable fullscreen support for video player
//                webChromeClient = object : android.webkit.WebChromeClient() {
//                    private var customView: View? = null
//                    private var customViewCallback: CustomViewCallback? = null
//                    private var originalSystemUiVisibility: Int = 0
//
//                    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
//                        if (customView != null) {
//                            callback?.onCustomViewHidden()
//                            return
//                        }
//
//                        customView = view
//                        customViewCallback = callback
//
//                        // Hide the WebView and show fullscreen video view
//                        this@apply.visibility = View.GONE
//                        container.addView(
//                            view,
//                            android.widget.FrameLayout.LayoutParams(
//                                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
//                                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
//                            )
//                        )
//
//                        // Hide system UI for true fullscreen
//                        originalSystemUiVisibility = requireActivity().window.decorView.systemUiVisibility
//                        requireActivity().window.decorView.systemUiVisibility = (
//                                View.SYSTEM_UI_FLAG_FULLSCREEN
//                                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
//                                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
//                                )
//
//                        Log.d("ModalFragment", "YouTube fullscreen enabled")
//                    }
//
//                    override fun onHideCustomView() {
//                        if (customView == null) return
//
//                        // Remove fullscreen view and restore WebView
//                        container.removeView(customView)
//                        this@apply.visibility = View.VISIBLE
//                        customView = null
//                        customViewCallback?.onCustomViewHidden()
//                        customViewCallback = null
//
//                        // Restore system UI
//                        requireActivity().window.decorView.systemUiVisibility = originalSystemUiVisibility
//
//                        Log.d("ModalFragment", "YouTube fullscreen disabled")
//                    }
//                }
//
//                // Use youtube-nocookie.com - privacy-enhanced mode without consent
//                val embedHtml = """
//                    <!DOCTYPE html>
//                    <html style="height: 100%;">
//                    <head>
//                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
//                        <style>
//                            * { margin: 0; padding: 0; box-sizing: border-box; }
//                            html, body {
//                                width: 100%;
//                                height: 100%;
//                                overflow: hidden;
//                                background: #000;
//                            }
//                            iframe {
//                                position: absolute;
//                                top: 0;
//                                left: 0;
//                                width: 100% !important;
//                                height: 100% !important;
//                                border: none;
//                            }
//                        </style>
//                    </head>
//                    <body style="height: 100%;">
//                        <iframe src="https://www.youtube-nocookie.com/embed/$videoId"
//                                frameborder="0"
//                                allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture"
//                                allowfullscreen
//                                style="width: 100%; height: 100%;">
//                        </iframe>
//                    </body>
//                    </html>
//                """.trimIndent()
//
//                loadDataWithBaseURL("https://www.youtube-nocookie.com", embedHtml, "text/html", "UTF-8", null)
//                Log.d("ModalFragment", "Loading YouTube nocookie embed: $videoId")
//            }
//
//            container.addView(webView)
//        } else{
//            // Use ExoPlayer for other video URLs
//            exoPlayer = ExoPlayer.Builder(requireContext()).build().apply {
//                setMediaItem(MediaItem.fromUri(url))
//                prepare()
//                playWhenReady = false
//            }
//
//            val playerView = PlayerView(requireContext()).apply {
//                player = exoPlayer
//            }
//            container.addView(playerView)
//        }
//    }
//
//    private fun showYouTubeFallback(container: FrameLayout, url: String) {
//        requireActivity().runOnUiThread {
//            container.removeAllViews()
//
//            val watchButton = Button(requireContext()).apply {
//                text = "▶ Watch on YouTube"
//                textSize = 16f
//                setPadding(32, 32, 32, 32)
//                setOnClickListener {
//                    openUrl(url)
//                }
//            }
//
//            val messageLayout = LinearLayout(requireContext()).apply {
//                orientation = LinearLayout.VERTICAL
//                gravity = android.view.Gravity.CENTER
//                setPadding(48, 48, 48, 48)
//
//                addView(TextView(requireContext()).apply {
//                    text = "📹 YouTube Video"
//                    textSize = 18f
//                    gravity = android.view.Gravity.CENTER
//                    setPadding(0, 0, 0, 24)
//                })
//
//                addView(watchButton)
//
//                addView(TextView(requireContext()).apply {
//                    text = "This video cannot be embedded. Tap to watch on YouTube."
//                    textSize = 12f
//                    setTextColor(android.graphics.Color.GRAY)
//                    gravity = android.view.Gravity.CENTER
//                    setPadding(0, 16, 0, 0)
//                })
//            }
//
//            container.addView(messageLayout)
//            Log.d("ModalFragment", "YouTube fallback button shown for URL: $url")
//        }
//    }
//
//    private fun setupAudioPlayer(container: FrameLayout, url: String) {
//        exoPlayer = ExoPlayer.Builder(requireContext()).build().apply {
//            setMediaItem(MediaItem.fromUri(url))
//            prepare()
//            playWhenReady = false
//        }
//
//        val playerView = PlayerView(requireContext()).apply {
//            player = exoPlayer
//        }
//        container.addView(playerView)
//    }
//
//    private fun openUrl(url: String) {
//        try {
//            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
//            startActivity(intent)
//            Logger.log("ModalFragment", "Opened URL: $url")
//        } catch (e: Exception) {
//            Logger.log("ModalFragment", "Failed to open URL: ${e.message}")
//        }
//    }
//
//    private fun cleanupPlayer() {
//        exoPlayer?.release()
//        exoPlayer = null
//    }
//
//    override fun onDestroyView() {
//        cleanupPlayer()
//        super.onDestroyView()
//    }
//}


//V2

//import android.app.Dialog
//import android.content.Intent
//import android.net.Uri
//import android.os.Bundle
//import android.util.Log
//import android.view.View
//import android.widget.Button
//import android.widget.CheckBox
//import android.widget.EditText
//import android.widget.FrameLayout
//import android.widget.ImageView
//import android.widget.LinearLayout
//import android.widget.RadioButton
//import android.widget.RadioGroup
//import android.widget.RatingBar
//import android.widget.SeekBar
//import android.widget.TextView
//import android.widget.Toast
//import androidx.core.os.bundleOf
//import androidx.fragment.app.DialogFragment
//import androidx.media3.common.MediaItem
//import androidx.media3.exoplayer.ExoPlayer
//import androidx.media3.ui.PlayerView
//import com.bumptech.glide.Glide
//import com.google.android.material.dialog.MaterialAlertDialogBuilder
//import com.google.gson.Gson
//import tech.bubbl.sdk.BubblSdk
//import tech.bubbl.sdk.notifications.NotificationRouter
//import tech.bubbl.sdk.utils.Logger
//import tech.bubbl.sdk.models.QuestionType
//import tech.bubbl.sdk.models.SurveyAnswer
//import tech.bubbl.sdk.models.ChoiceSelection
//import kotlin.apply
//import kotlin.jvm.java
//import kotlin.let
//import kotlin.text.contains
//import kotlin.text.ifBlank
//import kotlin.text.isNullOrBlank
//import kotlin.text.lowercase
//import kotlin.text.replace
//import kotlin.text.trimIndent
//import kotlin.to
//
//class ModalFragment : DialogFragment() {
//
//    companion object {
//        private const val ARG_NOTIFICATION = "notification_json"
//
//        fun newInstance(notification: NotificationRouter.DomainNotification): ModalFragment {
//            return ModalFragment().apply {
//                arguments = bundleOf(ARG_NOTIFICATION to Gson().toJson(notification))
//            }
//        }
//    }
//
//    private var exoPlayer: ExoPlayer? = null
//
//    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
//        Logger.log("ModalFragment", "🎭 onCreateDialog called!")
//
//        val json = requireArguments().getString(ARG_NOTIFICATION)
//            ?: throw kotlin.IllegalStateException("No notification data provided")
//
//        Logger.log("ModalFragment", "📦 Got notification JSON: ${json.take(100)}...")
//
//        val notification = Gson().fromJson(
//            json,
//            NotificationRouter.DomainNotification::class.java
//        )
//
//        Logger.log("ModalFragment", "✅ Parsed notification id=${notification.id}, headline='${notification.headline}'")
//
//        // Inflate custom view
//        val view = requireActivity().layoutInflater.inflate(
//            R.layout.dialog_notification,
//            null,
//            false
//        )
//
//        setupView(view, notification)
//
//        // Track notification delivered when modal is shown
//        val isSurvey = notification.mediaType.isNullOrBlank() || notification.mediaType?.lowercase() == "survey"
//        if (isSurvey) {
//            BubblSdk.trackSurveyEvent(
//                notificationId = notification.id.toString(),
//                locationId = notification.locationId,
//                activity = "notification_delivered"
//            ) { success ->
//                Log.d("ModalFragment", "Survey notification_delivered tracked: $success")
//            }
//        }
//
//        return MaterialAlertDialogBuilder(requireContext())
//            .setView(view)
//            .setPositiveButton("Close") { _, _ ->
//                trackDismissal(notification)
//                cleanupPlayer()
//            }
//            .setOnCancelListener {
//                trackDismissal(notification)
//                cleanupPlayer()
//            }
//            .create()
//    }
//
//    private fun setupView(view: View, notification: NotificationRouter.DomainNotification) {
//        // Set text content
//        view.findViewById<TextView>(R.id.tv_headline).text = notification.headline
//        view.findViewById<TextView>(R.id.tv_body).text = notification.body.ifBlank { "(no body)" }
//
//        // Handle media based on type
//        val imageView = view.findViewById<ImageView>(R.id.iv_media)
//        val videoContainer = view.findViewById<FrameLayout>(R.id.video_container)
//        val audioContainer = view.findViewById<FrameLayout>(R.id.audio_container)
//        val surveyContainer = view.findViewById<LinearLayout>(R.id.survey_container)
//
//        // Check if this is a survey notification (no media or "survey" type)
//        val mediaType = notification.mediaType
//        val isSurvey = mediaType.isNullOrBlank() || mediaType?.lowercase() == "survey"
//
//        // Track CTA engagement (but not for surveys - surveys use trackSurveyEvent)
//        if (!isSurvey) {
//            BubblSdk.cta(notification.id, notification.locationId)
//            Logger.log("ModalFragment", "CTA tracked: id=${notification.id}, locationId=${notification.locationId}")
//        }
//
//        when {
//            isSurvey -> {
//                // Show survey UI
//                surveyContainer.visibility = View.VISIBLE
//                setupSurveyView(view, notification)
//            }
//
//            notification.mediaType?.lowercase() == "image" -> {
//                imageView.visibility = View.VISIBLE
//                notification.mediaUrl?.let { url ->
//                    Glide.with(imageView)
//                        .load(url)
//                        .into(imageView)
//                }
//            }
//
//            notification.mediaType?.lowercase() == "video" -> {
//                videoContainer.visibility = View.VISIBLE
//                notification.mediaUrl?.let { url ->
//                    setupVideoPlayer(videoContainer, url)
//                }
//            }
//
//            notification.mediaType?.lowercase() == "audio" -> {
//                audioContainer.visibility = View.VISIBLE
//                notification.mediaUrl?.let { url ->
//                    setupAudioPlayer(audioContainer, url)
//                }
//            }
//
//            else -> {
//                // Text only - no media
//                Logger.log("ModalFragment", "Text-only notification")
//            }
//        }
//
//        // Setup CTA button
//        val ctaButton = view.findViewById<Button>(R.id.btn_cta)
//        val ctaUrl = notification.ctaUrl
//        if (!notification.ctaLabel.isNullOrBlank() && !ctaUrl.isNullOrBlank()) {
//            ctaButton.text = notification.ctaLabel
//            ctaButton.visibility = View.VISIBLE
//            ctaButton.setOnClickListener {
//                openUrl(ctaUrl)
//                dismiss()
//            }
//        } else {
//            ctaButton.visibility = View.GONE
//        }
//    }
//
//    private fun setupSurveyView(view: View, notification: NotificationRouter.DomainNotification) {
//        val questionsContainer = view.findViewById<LinearLayout>(R.id.survey_questions_container)
//        val submitBtn = view.findViewById<Button>(R.id.btn_survey_submit)
//
//        // Store question views for later retrieval
//        val questionViews = mutableMapOf<Int, View>()
//
//        // Parse and render each question
//        notification.questions?.sortedBy { it.position }?.forEach { question ->
//            val questionView = createQuestionView(question)
//            questionView?.let {
//                questionsContainer.addView(it)
//                questionViews[question.id] = it
//            }
//        }
//
//        // Track CTA engagement when survey is opened
//        BubblSdk.trackSurveyEvent(
//            notificationId = notification.id.toString(),
//            locationId = notification.locationId,
//            activity = "cta_engagement"
//        ) { success ->
//            Log.d("ModalFragment", "Survey cta_engagement tracked: $success")
//        }
//
//        submitBtn.setOnClickListener {
//            // Collect all answers
//            val answers = mutableListOf<SurveyAnswer>()
//
//            notification.questions?.forEach { question ->
//                val questionView = questionViews[question.id]
//                val answer = collectAnswer(question, questionView)
//                answer?.let { answers.add(it) }
//            }
//
//            Log.d("ModalFragment", "Survey submitted: ${answers.size} answers, notifId=${notification.id}, locationId=${notification.locationId}")
//
//            // Dismiss immediately to improve UX
//            dismiss()
//
//            // Submit survey response via BubblSdk
//            BubblSdk.submitSurveyResponse(
//                notificationId = notification.id.toString(),
//                locationId = notification.locationId,
//                answers = answers
//            ) { success ->
//                // Use context instead of requireContext() since the fragment might be detached
//                context?.let { ctx ->
//                    if (success) {
//                        Log.d("ModalFragment", "Survey submission successful")
//                        Toast.makeText(ctx, "Thank you for your feedback!", Toast.LENGTH_SHORT).show()
//                    } else {
//                        Log.e("ModalFragment", "Survey submission failed")
//                        Toast.makeText(ctx, "Failed to submit survey. Please try again.", Toast.LENGTH_LONG).show()
//                    }
//                } ?: run {
//                    // Fragment already detached, just log
//                    if (success) {
//                        Log.d("ModalFragment", "Survey submission successful (fragment detached)")
//                    } else {
//                        Log.e("ModalFragment", "Survey submission failed (fragment detached)")
//                    }
//                }
//            }
//        }
//    }
//
//    private fun createQuestionView(question: tech.bubbl.sdk.models.SurveyQuestion): View? {
//        // Skip if null type
//        if (question.question_type == null) {
//            Log.w("ModalFragment", "Skipping question with null type: ${question.id}")
//            return null
//        }
//
//        // Inflate appropriate layout based on question type
//        val layoutId = when (question.question_type) {
//            QuestionType.BOOLEAN -> R.layout.survey_question_boolean
//            QuestionType.NUMBER -> R.layout.survey_question_number
//            QuestionType.OPEN_ENDED -> R.layout.survey_question_open_ended
//            QuestionType.RATING -> R.layout.survey_question_rating
//            QuestionType.SLIDER -> R.layout.survey_question_slider
//            QuestionType.SINGLE_CHOICE -> R.layout.survey_question_single_choice
//            QuestionType.MULTIPLE_CHOICE -> R.layout.survey_question_multiple_choice
//            null -> return null
//        }
//
//        val view = requireActivity().layoutInflater.inflate(layoutId, null, false)
//
//        // Set question number and text
//        view.findViewById<TextView>(R.id.tv_question_number)?.text = "Q${question.position}."
//        view.findViewById<TextView>(R.id.tv_question_text)?.text = question.question
//
//        // Setup specific input type
//        when (question.question_type) {
//            QuestionType.SLIDER -> setupSliderInput(view, question)
//            QuestionType.SINGLE_CHOICE -> setupSingleChoiceInput(view, question)
//            QuestionType.MULTIPLE_CHOICE -> setupMultipleChoiceInput(view, question)
//            else -> {} // Other types are already configured in the layout
//        }
//
//        view.tag = question.id
//        return view
//    }
//
//    private fun trackDismissal(notification: NotificationRouter.DomainNotification) {
//        // Only track dismissal for surveys (not for regular notifications)
//        val mediaType = notification.mediaType
//        val isSurvey = mediaType.isNullOrBlank() || mediaType?.lowercase() == "survey"
//
//        if (isSurvey) {
//            BubblSdk.trackSurveyEvent(
//                notificationId = notification.id.toString(),
//                locationId = notification.locationId,
//                activity = "dismissed"
//            ) { success ->
//                Log.d("ModalFragment", "Survey dismissal tracked: $success")
//            }
//        }
//    }
//
//    private fun setupSliderInput(view: View, question: tech.bubbl.sdk.models.SurveyQuestion) {
//        val seekBar = view.findViewById<SeekBar>(R.id.seekbar)
//        val valueText = view.findViewById<TextView>(R.id.tv_slider_value)
//        val minLabel = view.findViewById<TextView>(R.id.tv_slider_min_label)
//        val maxLabel = view.findViewById<TextView>(R.id.tv_slider_max_label)
//
//        val choices = question.choices?.sortedBy { it.position } ?: emptyList()
//        if (choices.isNotEmpty()) {
//            // If only one choice, make slider 0-0 range and show the choice name
//            val maxValue = if (choices.size == 1) 0 else choices.size - 1
//            seekBar.max = maxValue
//
//            // Show choice labels or fallback to Min/Max
//            if (choices.size == 1) {
//                minLabel.text = ""
//                maxLabel.text = ""
//                valueText.text = choices[0].choice
//            } else {
//                minLabel.text = choices.firstOrNull()?.choice ?: "Min"
//                maxLabel.text = choices.lastOrNull()?.choice ?: "Max"
//                valueText.text = choices.firstOrNull()?.choice ?: "0"
//            }
//
//            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
//                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
//                    valueText.text = choices.getOrNull(progress)?.choice ?: progress.toString()
//                }
//                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
//                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
//            })
//        } else {
//            seekBar.max = 10
//            minLabel.text = "0"
//            maxLabel.text = "10"
//            valueText.text = "0"
//
//            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
//                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
//                    valueText.text = progress.toString()
//                }
//                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
//                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
//            })
//        }
//    }
//
//    private fun setupSingleChoiceInput(view: View, question: tech.bubbl.sdk.models.SurveyQuestion) {
//        val radioGroup = view.findViewById<RadioGroup>(R.id.radio_group)
//        question.choices?.sortedBy { it.position }?.forEach { choice ->
//            val radioButton = RadioButton(requireContext()).apply {
//                text = choice.choice
//                id = View.generateViewId()
//                tag = choice.id
//                setPadding(
//                    resources.getDimensionPixelSize(R.dimen.survey_choice_padding),
//                    resources.getDimensionPixelSize(R.dimen.survey_choice_padding),
//                    resources.getDimensionPixelSize(R.dimen.survey_choice_padding),
//                    resources.getDimensionPixelSize(R.dimen.survey_choice_padding)
//                )
//                setTextColor(resources.getColor(R.color.survey_text_primary, null))
//                textSize = 15f
//                buttonTintList = android.content.res.ColorStateList.valueOf(
//                    resources.getColor(R.color.survey_primary, null)
//                )
//                setBackgroundResource(R.drawable.survey_choice_background)
//                layoutParams = RadioGroup.LayoutParams(
//                    RadioGroup.LayoutParams.MATCH_PARENT,
//                    RadioGroup.LayoutParams.WRAP_CONTENT
//                ).apply {
//                    bottomMargin = resources.getDimensionPixelSize(R.dimen.survey_choice_margin)
//                }
//            }
//            radioGroup.addView(radioButton)
//        }
//    }
//
//    private fun setupMultipleChoiceInput(view: View, question: tech.bubbl.sdk.models.SurveyQuestion) {
//        val container = view.findViewById<LinearLayout>(R.id.choices_container)
//        question.choices?.sortedBy { it.position }?.forEach { choice ->
//            val checkBox = CheckBox(requireContext()).apply {
//                text = choice.choice
//                tag = choice.id
//                setPadding(
//                    resources.getDimensionPixelSize(R.dimen.survey_choice_padding),
//                    resources.getDimensionPixelSize(R.dimen.survey_choice_padding),
//                    resources.getDimensionPixelSize(R.dimen.survey_choice_padding),
//                    resources.getDimensionPixelSize(R.dimen.survey_choice_padding)
//                )
//                setTextColor(resources.getColor(R.color.survey_text_primary, null))
//                textSize = 15f
//                buttonTintList = android.content.res.ColorStateList.valueOf(
//                    resources.getColor(R.color.survey_primary, null)
//                )
//                setBackgroundResource(R.drawable.survey_choice_background)
//                layoutParams = LinearLayout.LayoutParams(
//                    LinearLayout.LayoutParams.MATCH_PARENT,
//                    LinearLayout.LayoutParams.WRAP_CONTENT
//                ).apply {
//                    bottomMargin = resources.getDimensionPixelSize(R.dimen.survey_choice_margin)
//                }
//            }
//            container.addView(checkBox)
//        }
//    }
//
//    private fun collectAnswer(question: tech.bubbl.sdk.models.SurveyQuestion, questionView: View?): SurveyAnswer? {
//        if (questionView == null) return null
//        if (question.question_type == null) return null
//
//        val questionId = question.id
//
//        return when (question.question_type) {
//            QuestionType.BOOLEAN -> {
//                val radioGroup = questionView.findViewById<RadioGroup>(R.id.radio_group)
//                val checkedId = radioGroup?.checkedRadioButtonId ?: -1
//                if (checkedId == -1) return null
//                val selectedButton = radioGroup?.findViewById<RadioButton>(checkedId)
//                val value = if (selectedButton?.tag == "yes") "true" else "false"
//                SurveyAnswer(
//                    question_id = questionId,
//                    type = "BOOLEAN",
//                    value = value
//                )
//            }
//
//            QuestionType.NUMBER -> {
//                val editText = questionView.findViewById<EditText>(R.id.et_number)
//                val value = editText?.text?.toString() ?: ""
//                if (value.isBlank()) return null
//                SurveyAnswer(
//                    question_id = questionId,
//                    type = "NUMBER",
//                    value = value
//                )
//            }
//
//            QuestionType.OPEN_ENDED -> {
//                val editText = questionView.findViewById<EditText>(R.id.et_answer)
//                val value = editText?.text?.toString() ?: ""
//                if (value.isBlank()) return null
//                SurveyAnswer(
//                    question_id = questionId,
//                    type = "OPEN_ENDED",
//                    value = value
//                )
//            }
//
//            QuestionType.RATING -> {
//                val ratingBar = questionView.findViewById<RatingBar>(R.id.rating_bar)
//                val rating = ratingBar?.rating?.toInt() ?: 0
//                if (rating == 0) return null
//                SurveyAnswer(
//                    question_id = questionId,
//                    type = "RATING",
//                    value = rating.toString()
//                )
//            }
//
//            QuestionType.SLIDER -> {
//                val seekBar = questionView.findViewById<SeekBar>(R.id.seekbar)
//                val progress = seekBar?.progress ?: 0
//                val choiceId = question.choices?.sortedBy { it.position }?.getOrNull(progress)?.id
//                if (choiceId == null) {
//                    Log.e("ModalFragment", "No choice found at index $progress for SLIDER question")
//                    return null
//                }
//                SurveyAnswer(
//                    question_id = questionId,
//                    type = "SLIDER",
//                    value = progress.toString(),
//                    choice = listOf(ChoiceSelection(choice_id = choiceId))
//                )
//            }
//
//            QuestionType.SINGLE_CHOICE -> {
//                val radioGroup = questionView.findViewById<RadioGroup>(R.id.radio_group)
//                val checkedId = radioGroup?.checkedRadioButtonId ?: -1
//                if (checkedId == -1) return null
//                val selectedButton = radioGroup?.findViewById<RadioButton>(checkedId)
//                val choiceId = selectedButton?.tag as? Int
//                if (choiceId == null) {
//                    Log.e("ModalFragment", "Invalid choice ID for SINGLE_CHOICE question")
//                    return null
//                }
//                SurveyAnswer(
//                    question_id = questionId,
//                    type = "SINGLE_CHOICE",
//                    value = selectedButton.text.toString(),
//                    choice = listOf(ChoiceSelection(choice_id = choiceId))
//                )
//            }
//
//            QuestionType.MULTIPLE_CHOICE -> {
//                val container = questionView.findViewById<LinearLayout>(R.id.choices_container)
//                val selectedChoices = mutableListOf<ChoiceSelection>()
//                if (container != null) {
//                    for (i in 0 until container.childCount) {
//                        val checkBox = container.getChildAt(i) as? CheckBox
//                        if (checkBox?.isChecked == true) {
//                            val choiceId = checkBox.tag as? Int
//                            if (choiceId != null) {
//                                selectedChoices.add(ChoiceSelection(choice_id = choiceId))
//                            } else {
//                                Log.e("ModalFragment", "Invalid choice ID in MULTIPLE_CHOICE, skipping")
//                            }
//                        }
//                    }
//                }
//                if (selectedChoices.isEmpty()) return null
//                SurveyAnswer(
//                    question_id = questionId,
//                    type = "MULTIPLE_CHOICE",
//                    value = "YES",
//                    choice = selectedChoices
//                )
//            }
//            null -> null
//        }
//    }
//
//    private fun setupVideoPlayer(container: FrameLayout, url: String) {
//        // For YouTube URLs, use youtube-nocookie.com embed (privacy-enhanced, no consent screen)
//        if (url.contains("youtube.com", ignoreCase = true) || url.contains("youtu.be", ignoreCase = true)) {
//            // Extract video ID
//            val videoId = if (url.contains("/embed/")) {
//                url.substringAfter("/embed/").substringBefore("?")
//            } else {
//                url.substringAfter("v=").substringBefore("&")
//            }
//
//            val webView = android.webkit.WebView(requireContext()).apply {
//                settings.apply {
//                    javaScriptEnabled = true
//                    domStorageEnabled = true
//                    mediaPlaybackRequiresUserGesture = false
//                }
//
//                webViewClient = android.webkit.WebViewClient()
//
//                // Enable fullscreen support for video player
//                webChromeClient = object : android.webkit.WebChromeClient() {
//                    private var customView: View? = null
//                    private var customViewCallback: CustomViewCallback? = null
//                    private var originalSystemUiVisibility: Int = 0
//
//                    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
//                        if (customView != null) {
//                            callback?.onCustomViewHidden()
//                            return
//                        }
//
//                        customView = view
//                        customViewCallback = callback
//
//                        // Hide the WebView and show fullscreen video view
//                        this@apply.visibility = View.GONE
//                        container.addView(
//                            view,
//                            android.widget.FrameLayout.LayoutParams(
//                                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
//                                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
//                            )
//                        )
//
//                        // Hide system UI for true fullscreen
//                        originalSystemUiVisibility = requireActivity().window.decorView.systemUiVisibility
//                        requireActivity().window.decorView.systemUiVisibility = (
//                                View.SYSTEM_UI_FLAG_FULLSCREEN
//                                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
//                                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
//                                )
//
//                        Log.d("ModalFragment", "YouTube fullscreen enabled")
//                    }
//
//                    override fun onHideCustomView() {
//                        if (customView == null) return
//
//                        // Remove fullscreen view and restore WebView
//                        container.removeView(customView)
//                        this@apply.visibility = View.VISIBLE
//                        customView = null
//                        customViewCallback?.onCustomViewHidden()
//                        customViewCallback = null
//
//                        // Restore system UI
//                        requireActivity().window.decorView.systemUiVisibility = originalSystemUiVisibility
//
//                        Log.d("ModalFragment", "YouTube fullscreen disabled")
//                    }
//                }
//
//                // Use youtube-nocookie.com - privacy-enhanced mode without consent
//                val embedHtml = """
//                    <!DOCTYPE html>
//                    <html style="height: 100%;">
//                    <head>
//                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
//                        <style>
//                            * { margin: 0; padding: 0; box-sizing: border-box; }
//                            html, body {
//                                width: 100%;
//                                height: 100%;
//                                overflow: hidden;
//                                background: #000;
//                            }
//                            iframe {
//                                position: absolute;
//                                top: 0;
//                                left: 0;
//                                width: 100% !important;
//                                height: 100% !important;
//                                border: none;
//                            }
//                        </style>
//                    </head>
//                    <body style="height: 100%;">
//                        <iframe src="https://www.youtube-nocookie.com/embed/$videoId"
//                                frameborder="0"
//                                allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture"
//                                allowfullscreen
//                                style="width: 100%; height: 100%;">
//                        </iframe>
//                    </body>
//                    </html>
//                """.trimIndent()
//
//                loadDataWithBaseURL("https://www.youtube-nocookie.com", embedHtml, "text/html", "UTF-8", null)
//                Log.d("ModalFragment", "Loading YouTube nocookie embed: $videoId")
//            }
//
//            container.addView(webView)
//        } else{
//            // Use ExoPlayer for other video URLs
//            exoPlayer = ExoPlayer.Builder(requireContext()).build().apply {
//                setMediaItem(MediaItem.fromUri(url))
//                prepare()
//                playWhenReady = false
//            }
//
//            val playerView = PlayerView(requireContext()).apply {
//                player = exoPlayer
//            }
//            container.addView(playerView)
//        }
//    }
//
//    private fun showYouTubeFallback(container: FrameLayout, url: String) {
//        requireActivity().runOnUiThread {
//            container.removeAllViews()
//
//            val watchButton = Button(requireContext()).apply {
//                text = "▶ Watch on YouTube"
//                textSize = 16f
//                setPadding(32, 32, 32, 32)
//                setOnClickListener {
//                    openUrl(url)
//                }
//            }
//
//            val messageLayout = LinearLayout(requireContext()).apply {
//                orientation = LinearLayout.VERTICAL
//                gravity = android.view.Gravity.CENTER
//                setPadding(48, 48, 48, 48)
//
//                addView(TextView(requireContext()).apply {
//                    text = "📹 YouTube Video"
//                    textSize = 18f
//                    gravity = android.view.Gravity.CENTER
//                    setPadding(0, 0, 0, 24)
//                })
//
//                addView(watchButton)
//
//                addView(TextView(requireContext()).apply {
//                    text = "This video cannot be embedded. Tap to watch on YouTube."
//                    textSize = 12f
//                    setTextColor(android.graphics.Color.GRAY)
//                    gravity = android.view.Gravity.CENTER
//                    setPadding(0, 16, 0, 0)
//                })
//            }
//
//            container.addView(messageLayout)
//            Log.d("ModalFragment", "YouTube fallback button shown for URL: $url")
//        }
//    }
//
//    private fun setupAudioPlayer(container: FrameLayout, url: String) {
//        exoPlayer = ExoPlayer.Builder(requireContext()).build().apply {
//            setMediaItem(MediaItem.fromUri(url))
//            prepare()
//            playWhenReady = false
//        }
//
//        val playerView = PlayerView(requireContext()).apply {
//            player = exoPlayer
//        }
//        container.addView(playerView)
//    }
//
//    private fun openUrl(url: String) {
//        try {
//            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
//            startActivity(intent)
//            Logger.log("ModalFragment", "Opened URL: $url")
//        } catch (e: Exception) {
//            Logger.log("ModalFragment", "Failed to open URL: ${e.message}")
//        }
//    }
//
//    private fun cleanupPlayer() {
//        exoPlayer?.release()
//        exoPlayer = null
//    }
//
//    override fun onDestroyView() {
//        cleanupPlayer()
//        super.onDestroyView()
//    }
//}

//V1
//import android.app.Dialog
//import android.content.Intent
//import android.net.Uri
//import android.os.Bundle
//import android.util.Log
//import android.view.View
//import android.webkit.WebView
//import android.widget.Button
//import android.widget.CheckBox
//import android.widget.EditText
//import android.widget.FrameLayout
//import android.widget.ImageView
//import android.widget.LinearLayout
//import android.widget.RadioButton
//import android.widget.RadioGroup
//import android.widget.RatingBar
//import android.widget.SeekBar
//import android.widget.TextView
//import android.widget.Toast
//import androidx.core.os.bundleOf
//import androidx.fragment.app.DialogFragment
//import androidx.media3.common.MediaItem
//import androidx.media3.exoplayer.ExoPlayer
//import androidx.media3.ui.PlayerView
//import com.bumptech.glide.Glide
//import com.google.android.material.dialog.MaterialAlertDialogBuilder
//import com.google.gson.Gson
//import tech.bubbl.sdk.BubblSdk
//import tech.bubbl.sdk.notifications.NotificationRouter
//import tech.bubbl.sdk.utils.Logger
//import tech.bubbl.sdk.models.QuestionType
//import tech.bubbl.sdk.models.SurveyAnswer
//import tech.bubbl.sdk.models.ChoiceSelection
//import kotlin.apply
//import kotlin.jvm.java
//import kotlin.let
//import kotlin.text.contains
//import kotlin.text.ifBlank
//import kotlin.text.isNullOrBlank
//import kotlin.text.lowercase
//import kotlin.text.replace
//import kotlin.text.trimIndent
//import kotlin.to
//
//class ModalFragment : DialogFragment() {
//
//    companion object {
//        private const val ARG_NOTIFICATION = "notification_json"
//
//        fun newInstance(notification: NotificationRouter.DomainNotification): ModalFragment {
//            return ModalFragment().apply {
//                arguments = bundleOf(ARG_NOTIFICATION to Gson().toJson(notification))
//            }
//        }
//    }
//
//    private var exoPlayer: ExoPlayer? = null
//
//    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
//        Logger.log("ModalFragment", "🎭 onCreateDialog called!")
//
//        val json = requireArguments().getString(ARG_NOTIFICATION)
//            ?: throw kotlin.IllegalStateException("No notification data provided")
//
//        Logger.log("ModalFragment", "📦 Got notification JSON: ${json.take(100)}...")
//
//        val notification = Gson().fromJson(
//            json,
//            NotificationRouter.DomainNotification::class.java
//        )
//
//        Logger.log("ModalFragment", "✅ Parsed notification id=${notification.id}, headline='${notification.headline}'")
//
//        // Inflate custom view
//        val view = requireActivity().layoutInflater.inflate(
//            R.layout.dialog_notification,
//            null,
//            false
//        )
//
//        setupView(view, notification)
//
//        return MaterialAlertDialogBuilder(requireContext())
//            .setView(view)
//            .setPositiveButton("Close") { _, _ ->
//                trackDismissal(notification)
//                cleanupPlayer()
//            }
//            .setOnCancelListener {
//                trackDismissal(notification)
//                cleanupPlayer()
//            }
//            .create()
//    }
//
//    private fun setupView(view: View, notification: NotificationRouter.DomainNotification) {
//        // Set text content
//        view.findViewById<TextView>(R.id.tv_headline).text = notification.headline
//        view.findViewById<TextView>(R.id.tv_body).text = notification.body.ifBlank { "(no body)" }
//
//        // Handle media based on type
//        val imageView = view.findViewById<ImageView>(R.id.iv_media)
//        val videoContainer = view.findViewById<FrameLayout>(R.id.video_container)
//        val audioContainer = view.findViewById<FrameLayout>(R.id.audio_container)
//        val surveyContainer = view.findViewById<LinearLayout>(R.id.survey_container)
//
//        // Check if this is a survey notification (no media or "survey" type)
//        val mediaType = notification.mediaType
//        val isSurvey = mediaType.isNullOrBlank() || mediaType?.lowercase() == "survey"
//
//        // Track CTA engagement (but not for surveys - surveys use trackSurveyEvent)
//        if (!isSurvey) {
//            BubblSdk.cta(notification.id, notification.locationId)
//            Logger.log("ModalFragment", "CTA tracked: id=${notification.id}, locationId=${notification.locationId}")
//        }
//
//        when {
//            isSurvey -> {
//                // Show survey UI
//                surveyContainer.visibility = View.VISIBLE
//                setupSurveyView(view, notification)
//            }
//
//            notification.mediaType?.lowercase() == "image" -> {
//                imageView.visibility = View.VISIBLE
//                notification.mediaUrl?.let { url ->
//                    Glide.with(imageView)
//                        .load(url)
//                        .into(imageView)
//                }
//            }
//
//            notification.mediaType?.lowercase() == "video" -> {
//                videoContainer.visibility = View.VISIBLE
//                notification.mediaUrl?.let { url ->
//                    setupVideoPlayer(videoContainer, url)
//                }
//            }
//
//            notification.mediaType?.lowercase() == "audio" -> {
//                audioContainer.visibility = View.VISIBLE
//                notification.mediaUrl?.let { url ->
//                    setupAudioPlayer(audioContainer, url)
//                }
//            }
//
//            else -> {
//                // Text only - no media
//                Logger.log("ModalFragment", "Text-only notification")
//            }
//        }
//
//        // Setup CTA button
//        val ctaButton = view.findViewById<Button>(R.id.btn_cta)
//        val ctaUrl = notification.ctaUrl
//        if (!notification.ctaLabel.isNullOrBlank() && !ctaUrl.isNullOrBlank()) {
//            ctaButton.text = notification.ctaLabel
//            ctaButton.visibility = View.VISIBLE
//            ctaButton.setOnClickListener {
//                openUrl(ctaUrl)
//                dismiss()
//            }
//        } else {
//            ctaButton.visibility = View.GONE
//        }
//    }
//
//    private fun setupSurveyView(view: View, notification: NotificationRouter.DomainNotification) {
//        val questionsContainer = view.findViewById<LinearLayout>(R.id.survey_questions_container)
//        val submitBtn = view.findViewById<Button>(R.id.btn_survey_submit)
//
//        // Store question views for later retrieval
//        val questionViews = mutableMapOf<Int, View>()
//
//        // Parse and render each question
//        notification.questions?.sortedBy { it.position }?.forEach { question ->
//            val questionView = createQuestionView(question)
//            questionView?.let {
//                questionsContainer.addView(it)
//                questionViews[question.id] = it
//            }
//        }
//
//        // Track CTA engagement when survey is opened
//        BubblSdk.trackSurveyEvent(
//            notificationId = notification.id.toString(),
//            locationId = notification.locationId,
//            activity = "cta_engagement"
//        ) { success ->
//            Log.d("ModalFragment", "Survey cta_engagement tracked: $success")
//        }
//
//        submitBtn.setOnClickListener {
//            // Collect all answers
//            val answers = mutableListOf<SurveyAnswer>()
//
//            notification.questions?.forEach { question ->
//                val questionView = questionViews[question.id]
//                val answer = collectAnswer(question, questionView)
//                answer?.let { answers.add(it) }
//            }
//
//            Log.d("ModalFragment", "Survey submitted: ${answers.size} answers, notifId=${notification.id}, locationId=${notification.locationId}")
//
//            // Dismiss immediately to improve UX
//            dismiss()
//
//            // Submit survey response via BubblSdk
//            BubblSdk.submitSurveyResponse(
//                notificationId = notification.id.toString(),
//                locationId = notification.locationId,
//                answers = answers
//            ) { success ->
//                // Use context instead of requireContext() since the fragment might be detached
//                context?.let { ctx ->
//                    if (success) {
//                        Log.d("ModalFragment", "Survey submission successful")
//                        Toast.makeText(ctx, "Thank you for your feedback!", Toast.LENGTH_SHORT).show()
//                    } else {
//                        Log.e("ModalFragment", "Survey submission failed")
//                        Toast.makeText(ctx, "Failed to submit survey. Please try again.", Toast.LENGTH_LONG).show()
//                    }
//                } ?: run {
//                    // Fragment already detached, just log
//                    if (success) {
//                        Log.d("ModalFragment", "Survey submission successful (fragment detached)")
//                    } else {
//                        Log.e("ModalFragment", "Survey submission failed (fragment detached)")
//                    }
//                }
//            }
//        }
//    }
//
//    private fun createQuestionView(question: tech.bubbl.sdk.models.SurveyQuestion): View? {
//        val container = LinearLayout(requireContext()).apply {
//            orientation = LinearLayout.VERTICAL
//            layoutParams = LinearLayout.LayoutParams(
//                LinearLayout.LayoutParams.MATCH_PARENT,
//                LinearLayout.LayoutParams.WRAP_CONTENT
//            )
//            setPadding(0, 16, 0, 16)
//        }
//
//        // Question text
//        val questionText = TextView(requireContext()).apply {
//            text = question.question
//            textSize = 16f
//            setTextColor(android.graphics.Color.BLACK)
//            setPadding(0, 0, 0, 12)
//        }
//        container.addView(questionText)
//
//        // Create input based on question type (skip if null - shouldn't happen due to SDK filtering)
//        val inputView = when (question.question_type) {
//            QuestionType.BOOLEAN -> createBooleanInput()
//            QuestionType.NUMBER -> createNumberInput()
//            QuestionType.OPEN_ENDED -> createOpenEndedInput()
//            QuestionType.RATING -> createRatingInput()
//            QuestionType.SLIDER -> createSliderInput(question)
//            QuestionType.SINGLE_CHOICE -> createSingleChoiceInput(question)
//            QuestionType.MULTIPLE_CHOICE -> createMultipleChoiceInput(question)
//            null -> {
//                Log.w("ModalFragment", "Skipping question with null type: ${question.id}")
//                return null
//            }
//        }
//        container.addView(inputView)
//
//        container.tag = question.id
//        return container
//    }
//
//    private fun trackDismissal(notification: NotificationRouter.DomainNotification) {
//        // Only track dismissal for surveys (not for regular notifications)
//        val mediaType = notification.mediaType
//        val isSurvey = mediaType.isNullOrBlank() || mediaType?.lowercase() == "survey"
//
//        if (isSurvey) {
//            BubblSdk.trackSurveyEvent(
//                notificationId = notification.id.toString(),
//                locationId = notification.locationId,
//                activity = "dismissed"
//            ) { success ->
//                Log.d("ModalFragment", "Survey dismissal tracked: $success")
//            }
//        }
//    }
//
//    private fun createBooleanInput(): View {
//        return RadioGroup(requireContext()).apply {
//            orientation = RadioGroup.VERTICAL
//            addView(RadioButton(requireContext()).apply {
//                text = "Yes"
//                id = View.generateViewId()
//                tag = "yes"
//            })
//            addView(RadioButton(requireContext()).apply {
//                text = "No"
//                id = View.generateViewId()
//                tag = "no"
//            })
//        }
//    }
//
//    private fun createNumberInput(): View {
//        return EditText(requireContext()).apply {
//            inputType = android.text.InputType.TYPE_CLASS_NUMBER
//            hint = "Enter a number"
//        }
//    }
//
//    private fun createOpenEndedInput(): View {
//        return EditText(requireContext()).apply {
//            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
//            hint = "Your answer"
//            minLines = 3
//            gravity = android.view.Gravity.TOP or android.view.Gravity.START
//        }
//    }
//
//    private fun createRatingInput(): View {
//        return LinearLayout(requireContext()).apply {
//            orientation = LinearLayout.VERTICAL
//            addView(RatingBar(requireContext()).apply {
//                numStars = 5
//                stepSize = 1f
//                rating = 0f
//            })
//            addView(TextView(requireContext()).apply {
//                text = "Rate from 1 to 5 stars"
//                textSize = 12f
//                setTextColor(android.graphics.Color.GRAY)
//            })
//        }
//    }
//
//    private fun createSliderInput(question: tech.bubbl.sdk.models.SurveyQuestion): View {
//        return LinearLayout(requireContext()).apply {
//            orientation = LinearLayout.VERTICAL
//
//            val valueText = TextView(requireContext()).apply {
//                text = "0"
//                textSize = 14f
//                gravity = android.view.Gravity.CENTER
//            }
//            addView(valueText)
//
//            addView(SeekBar(requireContext()).apply {
//                max = question.choices?.size ?: 10
//                progress = 0
//                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
//                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
//                        valueText.text = progress.toString()
//                    }
//                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
//                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
//                })
//            })
//        }
//    }
//
//    private fun createSingleChoiceInput(question: tech.bubbl.sdk.models.SurveyQuestion): View {
//        return RadioGroup(requireContext()).apply {
//            orientation = RadioGroup.VERTICAL
//            question.choices?.sortedBy { it.position }?.forEach { choice ->
//                addView(RadioButton(requireContext()).apply {
//                    text = choice.choice
//                    id = View.generateViewId()
//                    tag = choice.id
//                })
//            }
//        }
//    }
//
//    private fun createMultipleChoiceInput(question: tech.bubbl.sdk.models.SurveyQuestion): View {
//        return LinearLayout(requireContext()).apply {
//            orientation = LinearLayout.VERTICAL
//            question.choices?.sortedBy { it.position }?.forEach { choice ->
//                addView(CheckBox(requireContext()).apply {
//                    text = choice.choice
//                    tag = choice.id
//                })
//            }
//        }
//    }
//
//    private fun collectAnswer(question: tech.bubbl.sdk.models.SurveyQuestion, questionView: View?): SurveyAnswer? {
//        if (questionView == null) return null
//        if (question.question_type == null) return null
//
//        // Question ID is Int in local SDK
//        val questionId = question.id
//
//        return when (question.question_type) {
//            QuestionType.BOOLEAN -> {
//                val radioGroup = (questionView as LinearLayout).getChildAt(1) as RadioGroup
//                val checkedId = radioGroup.checkedRadioButtonId
//                if (checkedId == -1) return null
//                val selectedButton = radioGroup.findViewById<RadioButton>(checkedId)
//                val value = if (selectedButton.tag == "yes") "true" else "false"
//                SurveyAnswer(
//                    question_id = questionId,
//                    type = "BOOLEAN",
//                    value = value
//                )
//            }
//
//            QuestionType.NUMBER -> {
//                val editText = (questionView as LinearLayout).getChildAt(1) as EditText
//                val value = editText.text.toString()
//                if (value.isBlank()) return null
//                SurveyAnswer(
//                    question_id = questionId,
//                    type = "NUMBER",
//                    value = value
//                )
//            }
//
//            QuestionType.OPEN_ENDED -> {
//                val editText = (questionView as LinearLayout).getChildAt(1) as EditText
//                val value = editText.text.toString()
//                if (value.isBlank()) return null
//                SurveyAnswer(
//                    question_id = questionId,
//                    type = "OPEN_ENDED",
//                    value = value
//                )
//            }
//
//            QuestionType.RATING -> {
//                val container = (questionView as LinearLayout).getChildAt(1) as LinearLayout
//                val ratingBar = container.getChildAt(0) as RatingBar
//                val rating = ratingBar.rating.toInt()
//                if (rating == 0) return null
//                SurveyAnswer(
//                    question_id = questionId,
//                    type = "RATING",
//                    value = rating.toString()
//                )
//            }
//
//            QuestionType.SLIDER -> {
//                val container = (questionView as LinearLayout).getChildAt(1) as LinearLayout
//                val seekBar = container.getChildAt(1) as SeekBar
//                val progress = seekBar.progress
//                val choiceId = question.choices?.getOrNull(progress)?.id
//                if (choiceId == null) {
//                    Log.e("ModalFragment", "No choice found at index $progress for SLIDER question")
//                    return null
//                }
//                SurveyAnswer(
//                    question_id = questionId,
//                    type = "SLIDER",
//                    value = progress.toString(),
//                    choice = listOf(ChoiceSelection(choice_id = choiceId))
//                )
//            }
//
//            QuestionType.SINGLE_CHOICE -> {
//                val radioGroup = (questionView as LinearLayout).getChildAt(1) as RadioGroup
//                val checkedId = radioGroup.checkedRadioButtonId
//                if (checkedId == -1) return null
//                val selectedButton = radioGroup.findViewById<RadioButton>(checkedId)
//                val choiceId = selectedButton.tag as? Int
//                if (choiceId == null) {
//                    Log.e("ModalFragment", "Invalid choice ID for SINGLE_CHOICE question")
//                    return null
//                }
//                SurveyAnswer(
//                    question_id = questionId,
//                    type = "SINGLE_CHOICE",
//                    value = selectedButton.text.toString(),
//                    choice = listOf(ChoiceSelection(choice_id = choiceId))
//                )
//            }
//
//            QuestionType.MULTIPLE_CHOICE -> {
//                val container = (questionView as LinearLayout).getChildAt(1) as LinearLayout
//                val selectedChoices = mutableListOf<ChoiceSelection>()
//                for (i in 0 until container.childCount) {
//                    val checkBox = container.getChildAt(i) as CheckBox
//                    if (checkBox.isChecked) {
//                        val choiceId = checkBox.tag as? Int
//                        if (choiceId != null) {
//                            selectedChoices.add(ChoiceSelection(choice_id = choiceId))
//                        } else {
//                            Log.e("ModalFragment", "Invalid choice ID in MULTIPLE_CHOICE, skipping")
//                        }
//                    }
//                }
//                if (selectedChoices.isEmpty()) return null
//                SurveyAnswer(
//                    question_id = questionId,
//                    type = "MULTIPLE_CHOICE",
//                    value = "YES",
//                    choice = selectedChoices
//                )
//            }
//            null -> null
//        }
//    }
//
//    private fun setupVideoPlayer(container: FrameLayout, url: String) {
//        // For YouTube URLs, use WebView with embedded player
//        if (url.contains("youtube.com") || url.contains("youtu.be")) {
//            val embedUrl = url.replace("watch?v=", "embed/")
//                .replace("youtu.be/", "youtube.com/embed/")
//
//            val webView = WebView(requireContext()).apply {
//                settings.javaScriptEnabled = true
//                loadData(
//                    """
//                    <html>
//                    <body style="margin:0;padding:0;">
//                        <iframe width="100%" height="100%"
//                                src="$embedUrl"
//                                frameborder="0"
//                                allowfullscreen>
//                        </iframe>
//                    </body>
//                    </html>
//                    """.trimIndent(),
//                    "text/html",
//                    "utf-8"
//                )
//            }
//            container.addView(webView)
//        } else {
//            // Use ExoPlayer for other video URLs
//            exoPlayer = ExoPlayer.Builder(requireContext()).build().apply {
//                setMediaItem(MediaItem.fromUri(url))
//                prepare()
//                playWhenReady = false
//            }
//
//            val playerView = PlayerView(requireContext()).apply {
//                player = exoPlayer
//            }
//            container.addView(playerView)
//        }
//    }
//
//    private fun setupAudioPlayer(container: FrameLayout, url: String) {
//        exoPlayer = ExoPlayer.Builder(requireContext()).build().apply {
//            setMediaItem(MediaItem.fromUri(url))
//            prepare()
//            playWhenReady = false
//        }
//
//        val playerView = PlayerView(requireContext()).apply {
//            player = exoPlayer
//        }
//        container.addView(playerView)
//    }
//
//    private fun openUrl(url: String) {
//        try {
//            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
//            startActivity(intent)
//            Logger.log("ModalFragment", "Opened URL: $url")
//        } catch (e: Exception) {
//            Logger.log("ModalFragment", "Failed to open URL: ${e.message}")
//        }
//    }
//
//    private fun cleanupPlayer() {
//        exoPlayer?.release()
//        exoPlayer = null
//    }
//
//    override fun onDestroyView() {
//        cleanupPlayer()
//        super.onDestroyView()
//    }
//}

//V1
//import android.app.Dialog
//import android.content.Intent
//import android.net.Uri
//import android.os.Bundle
//import android.util.Log
//import android.view.View
//import android.webkit.WebView
//import android.widget.Button
//import android.widget.CheckBox
//import android.widget.EditText
//import android.widget.FrameLayout
//import android.widget.ImageView
//import android.widget.LinearLayout
//import android.widget.RadioButton
//import android.widget.RadioGroup
//import android.widget.RatingBar
//import android.widget.SeekBar
//import android.widget.TextView
//import android.widget.Toast
//import androidx.core.os.bundleOf
//import androidx.fragment.app.DialogFragment
//import androidx.media3.common.MediaItem
//import androidx.media3.exoplayer.ExoPlayer
//import androidx.media3.ui.PlayerView
//import com.bumptech.glide.Glide
//import com.google.android.material.dialog.MaterialAlertDialogBuilder
//import com.google.gson.Gson
//import tech.bubbl.sdk.BubblSdk
//import tech.bubbl.sdk.notifications.NotificationRouter
//import tech.bubbl.sdk.utils.Logger
//import tech.bubbl.sdk.models.QuestionType
//import tech.bubbl.sdk.models.SurveyAnswer
//import tech.bubbl.sdk.models.ChoiceSelection
//import kotlin.apply
//import kotlin.jvm.java
//import kotlin.let
//import kotlin.text.contains
//import kotlin.text.ifBlank
//import kotlin.text.isNullOrBlank
//import kotlin.text.lowercase
//import kotlin.text.replace
//import kotlin.text.trimIndent
//import kotlin.to
//
//class ModalFragment : DialogFragment() {
//
//    companion object {
//        private const val ARG_NOTIFICATION = "notification_json"
//
//        fun newInstance(notification: NotificationRouter.DomainNotification): ModalFragment {
//            return ModalFragment().apply {
//                arguments = bundleOf(ARG_NOTIFICATION to Gson().toJson(notification))
//            }
//        }
//    }
//
//    private var exoPlayer: ExoPlayer? = null
//
//    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
//        val json = requireArguments().getString(ARG_NOTIFICATION)
//            ?: throw kotlin.IllegalStateException("No notification data provided")
//
//        val notification = Gson().fromJson(
//            json,
//            NotificationRouter.DomainNotification::class.java
//        )
//
//        // Inflate custom view
//        val view = requireActivity().layoutInflater.inflate(
//            R.layout.dialog_notification,
//            null,
//            false
//        )
//
//        setupView(view, notification)
//
//        return MaterialAlertDialogBuilder(requireContext())
//            .setView(view)
//            .setPositiveButton("Close") { _, _ ->
//                trackDismissal(notification)
//                cleanupPlayer()
//            }
//            .setOnCancelListener {
//                trackDismissal(notification)
//                cleanupPlayer()
//            }
//            .create()
//    }
//
//    private fun setupView(view: View, notification: NotificationRouter.DomainNotification) {
//        // Set text content
//        view.findViewById<TextView>(R.id.tv_headline).text = notification.headline
//        view.findViewById<TextView>(R.id.tv_body).text = notification.body.ifBlank { "(no body)" }
//
//        // Handle media based on type
//        val imageView = view.findViewById<ImageView>(R.id.iv_media)
//        val videoContainer = view.findViewById<FrameLayout>(R.id.video_container)
//        val audioContainer = view.findViewById<FrameLayout>(R.id.audio_container)
//        val surveyContainer = view.findViewById<LinearLayout>(R.id.survey_container)
//
//        // Check if this is a survey notification (no media or "survey" type)
//        val isSurvey = notification.mediaType.isNullOrBlank() || notification.mediaType!!.lowercase() == "survey"
//
//        // Track CTA engagement (but not for surveys - surveys use trackSurveyEvent)
//        if (!isSurvey) {
//            BubblSdk.cta(notification.id, notification.locationId)
//            Logger.log("ModalFragment", "CTA tracked: id=${notification.id}, locationId=${notification.locationId}")
//        }
//
//        when {
//            isSurvey -> {
//                // Show survey UI
//                surveyContainer.visibility = View.VISIBLE
//                setupSurveyView(view, notification)
//            }
//
//            notification.mediaType?.lowercase() == "image" -> {
//                imageView.visibility = View.VISIBLE
//                notification.mediaUrl?.let { url ->
//                    Glide.with(imageView)
//                        .load(url)
//                        .into(imageView)
//                }
//            }
//
//            notification.mediaType?.lowercase() == "video" -> {
//                videoContainer.visibility = View.VISIBLE
//                notification.mediaUrl?.let { url ->
//                    setupVideoPlayer(videoContainer, url)
//                }
//            }
//
//            notification.mediaType?.lowercase() == "audio" -> {
//                audioContainer.visibility = View.VISIBLE
//                notification.mediaUrl?.let { url ->
//                    setupAudioPlayer(audioContainer, url)
//                }
//            }
//
//            else -> {
//                // Text only - no media
//                Logger.log("ModalFragment", "Text-only notification")
//            }
//        }
//
//        // Setup CTA button
//        val ctaButton = view.findViewById<Button>(R.id.btn_cta)
//        val ctaUrl = notification.ctaUrl
//        if (!notification.ctaLabel.isNullOrBlank() && !ctaUrl.isNullOrBlank()) {
//            ctaButton.text = notification.ctaLabel
//            ctaButton.visibility = View.VISIBLE
//            ctaButton.setOnClickListener {
//                openUrl(ctaUrl)
//                dismiss()
//            }
//        } else {
//            ctaButton.visibility = View.GONE
//        }
//    }
//
//    private fun setupSurveyView(view: View, notification: NotificationRouter.DomainNotification) {
//        val questionsContainer = view.findViewById<LinearLayout>(R.id.survey_questions_container)
//        val submitBtn = view.findViewById<Button>(R.id.btn_survey_submit)
//
//        // Store question views for later retrieval
//        val questionViews = mutableMapOf<String, View>()
//
//        // Parse and render each question
//        notification.questions?.sortedBy { it.position }?.forEach { question ->
//            val questionView = createQuestionView(question)
//            questionsContainer.addView(questionView)
//            questionViews[question.id] = questionView
//        }
//
//        // Track CTA engagement when survey is opened
//        BubblSdk.trackSurveyEvent(
//            notificationId = notification.id.toString(),
//            locationId = notification.locationId,
//            activity = "cta_engagement"
//        ) { success ->
//            Log.d("ModalFragment", "Survey cta_engagement tracked: $success")
//        }
//
//        submitBtn.setOnClickListener {
//            // Collect all answers
//            val answers = mutableListOf<SurveyAnswer>()
//
//            notification.questions?.forEach { question ->
//                val questionView = questionViews[question.id]
//                val answer = collectAnswer(question, questionView)
//                answer?.let { answers.add(it) }
//            }
//
//            Log.d("ModalFragment", "Survey submitted: ${answers.size} answers, notifId=${notification.id}, locationId=${notification.locationId}")
//
//            // Submit survey response via BubblSdk
//            BubblSdk.submitSurveyResponse(
//                notificationId = notification.id.toString(),
//                locationId = notification.locationId,
//                answers = answers
//            ) { success ->
//                if (success) {
//                    Log.d("ModalFragment", "Survey submission successful")
//                    Toast.makeText(requireContext(), "Thank you for your feedback!", Toast.LENGTH_SHORT).show()
//                } else {
//                    Log.e("ModalFragment", "Survey submission failed")
//                    Toast.makeText(requireContext(), "Failed to submit survey. Please try again.", Toast.LENGTH_LONG).show()
//                }
//            }
//
//            dismiss()
//        }
//    }
//
//    private fun createQuestionView(question: tech.bubbl.sdk.models.SurveyQuestion): View {
//        val container = LinearLayout(requireContext()).apply {
//            orientation = LinearLayout.VERTICAL
//            layoutParams = LinearLayout.LayoutParams(
//                LinearLayout.LayoutParams.MATCH_PARENT,
//                LinearLayout.LayoutParams.WRAP_CONTENT
//            )
//            setPadding(0, 16, 0, 16)
//        }
//
//        // Question text
//        val questionText = TextView(requireContext()).apply {
//            text = question.question
//            textSize = 16f
//            setTextColor(android.graphics.Color.BLACK)
//            setPadding(0, 0, 0, 12)
//        }
//        container.addView(questionText)
//
//        // ✅ Safely resolve the type
//        val type = question.question_type ?: run {
//            Log.w("ModalFragment", "question_type is null for id=${question.id}. Falling back to OPEN_ENDED.")
//            QuestionType.OPEN_ENDED
//        }
//
//        // Create input based on question type
//        val inputView = when (type) {
//            QuestionType.BOOLEAN -> createBooleanInput()
//            QuestionType.NUMBER -> createNumberInput()
//            QuestionType.OPEN_ENDED -> createOpenEndedInput()
//            QuestionType.RATING -> createRatingInput()
//            QuestionType.SLIDER -> createSliderInput(question)
//            QuestionType.SINGLE_CHOICE -> createSingleChoiceInput(question)
//            QuestionType.MULTIPLE_CHOICE -> createMultipleChoiceInput(question)
//        }
//        container.addView(inputView)
//
//        container.tag = question.id
//        return container
//    }
//
//    private fun trackDismissal(notification: NotificationRouter.DomainNotification) {
//        // Only track dismissal for surveys (not for regular notifications)
//        val isSurvey = notification.mediaType.isNullOrBlank() ||
//                       notification.mediaType!!.lowercase() == "survey"
//
//        if (isSurvey) {
//            BubblSdk.trackSurveyEvent(
//                notificationId = notification.id.toString(),
//                locationId = notification.locationId,
//                activity = "dismissed"
//            ) { success ->
//                Log.d("ModalFragment", "Survey dismissal tracked: $success")
//            }
//        }
//    }
//
//    private fun createBooleanInput(): View {
//        return RadioGroup(requireContext()).apply {
//            orientation = RadioGroup.VERTICAL
//            addView(RadioButton(requireContext()).apply {
//                text = "Yes"
//                id = View.generateViewId()
//                tag = "yes"
//            })
//            addView(RadioButton(requireContext()).apply {
//                text = "No"
//                id = View.generateViewId()
//                tag = "no"
//            })
//        }
//    }
//
//    private fun createNumberInput(): View {
//        return EditText(requireContext()).apply {
//            inputType = android.text.InputType.TYPE_CLASS_NUMBER
//            hint = "Enter a number"
//        }
//    }
//
//    private fun createOpenEndedInput(): View {
//        return EditText(requireContext()).apply {
//            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
//            hint = "Your answer"
//            minLines = 3
//            gravity = android.view.Gravity.TOP or android.view.Gravity.START
//        }
//    }
//
//    private fun createRatingInput(): View {
//        return LinearLayout(requireContext()).apply {
//            orientation = LinearLayout.VERTICAL
//            addView(RatingBar(requireContext()).apply {
//                numStars = 5
//                stepSize = 1f
//                rating = 0f
//            })
//            addView(TextView(requireContext()).apply {
//                text = "Rate from 1 to 5 stars"
//                textSize = 12f
//                setTextColor(android.graphics.Color.GRAY)
//            })
//        }
//    }
//
//    private fun createSliderInput(question: tech.bubbl.sdk.models.SurveyQuestion): View {
//        return LinearLayout(requireContext()).apply {
//            orientation = LinearLayout.VERTICAL
//
//            val valueText = TextView(requireContext()).apply {
//                text = "0"
//                textSize = 14f
//                gravity = android.view.Gravity.CENTER
//            }
//            addView(valueText)
//
//            addView(SeekBar(requireContext()).apply {
//                max = question.choices?.size ?: 10
//                progress = 0
//                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
//                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
//                        valueText.text = progress.toString()
//                    }
//                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
//                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
//                })
//            })
//        }
//    }
//
//    private fun createSingleChoiceInput(question: tech.bubbl.sdk.models.SurveyQuestion): View {
//        return RadioGroup(requireContext()).apply {
//            orientation = RadioGroup.VERTICAL
//            question.choices?.sortedBy { it.position }?.forEach { choice ->
//                addView(RadioButton(requireContext()).apply {
//                    text = choice.choice
//                    id = View.generateViewId()
//                    tag = choice.id
//                })
//            }
//        }
//    }
//
//    private fun createMultipleChoiceInput(question: tech.bubbl.sdk.models.SurveyQuestion): View {
//        return LinearLayout(requireContext()).apply {
//            orientation = LinearLayout.VERTICAL
//            question.choices?.sortedBy { it.position }?.forEach { choice ->
//                addView(CheckBox(requireContext()).apply {
//                    text = choice.choice
//                    tag = choice.id
//                })
//            }
//        }
//    }
//
//    private fun collectAnswer(question: tech.bubbl.sdk.models.SurveyQuestion, questionView: View?): SurveyAnswer? {
//        if (questionView == null) return null
//
//        val type = question.question_type ?: run {
//            Log.w("ModalFragment", "collectAnswer: question_type null for id=${question.id}; skipping.")
//            return null
//        }
//
//        return when (type) {
//            QuestionType.BOOLEAN -> {
//                val radioGroup = (questionView as LinearLayout).getChildAt(1) as RadioGroup
//                val checkedId = radioGroup.checkedRadioButtonId
//                if (checkedId == -1) return null
//                val selectedButton = radioGroup.findViewById<RadioButton>(checkedId)
//                val value = if (selectedButton.tag == "yes") "true" else "false"
//                SurveyAnswer(
//                    question_id = question.id,
//                    type = "BOOLEAN",
//                    value = value
//                )
//            }
//
//            QuestionType.NUMBER -> {
//                val editText = (questionView as LinearLayout).getChildAt(1) as EditText
//                val value = editText.text.toString()
//                if (value.isBlank()) return null
//                SurveyAnswer(
//                    question_id = question.id,
//                    type = "NUMBER",
//                    value = value
//                )
//            }
//
//            QuestionType.OPEN_ENDED -> {
//                val editText = (questionView as LinearLayout).getChildAt(1) as EditText
//                val value = editText.text.toString()
//                if (value.isBlank()) return null
//                SurveyAnswer(
//                    question_id = question.id,
//                    type = "OPEN_ENDED",
//                    value = value
//                )
//            }
//
//            QuestionType.RATING -> {
//                val container = (questionView as LinearLayout).getChildAt(1) as LinearLayout
//                val ratingBar = container.getChildAt(0) as RatingBar
//                val rating = ratingBar.rating.toInt()
//                if (rating == 0) return null
//                SurveyAnswer(
//                    question_id = question.id,
//                    type = "RATING",
//                    value = rating.toString()
//                )
//            }
//
//            QuestionType.SLIDER -> {
//                val container = (questionView as LinearLayout).getChildAt(1) as LinearLayout
//                val seekBar = container.getChildAt(1) as SeekBar
//                val progress = seekBar.progress
//                val choiceId = question.choices?.getOrNull(progress)?.id ?: progress.toString()
//                SurveyAnswer(
//                    question_id = question.id,
//                    type = "SLIDER",
//                    value = progress.toString(),
//                    choice = listOf(ChoiceSelection(choice_id = choiceId))
//                )
//            }
//
//            QuestionType.SINGLE_CHOICE -> {
//                val radioGroup = (questionView as LinearLayout).getChildAt(1) as RadioGroup
//                val checkedId = radioGroup.checkedRadioButtonId
//                if (checkedId == -1) return null
//                val selectedButton = radioGroup.findViewById<RadioButton>(checkedId)
//                val choiceId = selectedButton.tag as String
//                SurveyAnswer(
//                    question_id = question.id,
//                    type = "SINGLE_CHOICE",
//                    value = selectedButton.text.toString(),
//                    choice = listOf(ChoiceSelection(choice_id = choiceId))
//                )
//            }
//
//            QuestionType.MULTIPLE_CHOICE -> {
//                val container = (questionView as LinearLayout).getChildAt(1) as LinearLayout
//                val selectedChoices = mutableListOf<ChoiceSelection>()
//                for (i in 0 until container.childCount) {
//                    val checkBox = container.getChildAt(i) as CheckBox
//                    if (checkBox.isChecked) {
//                        selectedChoices.add(ChoiceSelection(choice_id = checkBox.tag as String))
//                    }
//                }
//                if (selectedChoices.isEmpty()) return null
//                SurveyAnswer(
//                    question_id = question.id,
//                    type = "MULTIPLE_CHOICE",
//                    value = "YES",
//                    choice = selectedChoices
//                )
//            }
//        }
//    }
//
//    private fun setupVideoPlayer(container: FrameLayout, url: String) {
//        // For YouTube URLs, use WebView with embedded player
//        if (url.contains("youtube.com") || url.contains("youtu.be")) {
//            val embedUrl = url.replace("watch?v=", "embed/")
//                .replace("youtu.be/", "youtube.com/embed/")
//
//            val webView = WebView(requireContext()).apply {
//                settings.javaScriptEnabled = true
//                loadData(
//                    """
//                    <html>
//                    <body style="margin:0;padding:0;">
//                        <iframe width="100%" height="100%"
//                                src="$embedUrl"
//                                frameborder="0"
//                                allowfullscreen>
//                        </iframe>
//                    </body>
//                    </html>
//                    """.trimIndent(),
//                    "text/html",
//                    "utf-8"
//                )
//            }
//            container.addView(webView)
//        } else {
//            // Use ExoPlayer for other video URLs
//            exoPlayer = ExoPlayer.Builder(requireContext()).build().apply {
//                setMediaItem(MediaItem.fromUri(url))
//                prepare()
//                playWhenReady = false
//            }
//
//            val playerView = PlayerView(requireContext()).apply {
//                player = exoPlayer
//            }
//            container.addView(playerView)
//        }
//    }
//
//    private fun setupAudioPlayer(container: FrameLayout, url: String) {
//        exoPlayer = ExoPlayer.Builder(requireContext()).build().apply {
//            setMediaItem(MediaItem.fromUri(url))
//            prepare()
//            playWhenReady = false
//        }
//
//        val playerView = PlayerView(requireContext()).apply {
//            player = exoPlayer
//        }
//        container.addView(playerView)
//    }
//
//    private fun openUrl(url: String) {
//        try {
//            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
//            startActivity(intent)
//            Logger.log("ModalFragment", "Opened URL: $url")
//        } catch (e: Exception) {
//            Logger.log("ModalFragment", "Failed to open URL: ${e.message}")
//        }
//    }
//
//    private fun cleanupPlayer() {
//        exoPlayer?.release()
//        exoPlayer = null
//    }
//
//    override fun onDestroyView() {
//        cleanupPlayer()
//        super.onDestroyView()
//    }
//}
