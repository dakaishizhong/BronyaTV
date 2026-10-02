package tv.ember.client.ui

import android.os.Bundle
import androidx.fragment.app.DialogFragment
import tv.ember.client.i18n.*

class LanguagePickerDialog: DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?)=TvUi.dialog(requireContext())
        .setTitle("Language / 语言")
        .setSingleChoiceItems(arrayOf("English","简体中文"),if(AppLanguage.read(requireContext())=="zh") 1 else 0) { _,index ->
            val activity=requireActivity()
            val code=if(index==1) "zh" else "en"
            dismiss()
            if(AppLanguage.read(activity)!=code) { AppLanguage.save(activity,code);activity.recreate() }
        }.setNegativeButton(Tr.text(UiText.CANCEL_196),null).create()
}
