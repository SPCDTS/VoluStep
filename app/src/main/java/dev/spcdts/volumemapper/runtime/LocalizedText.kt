package dev.spcdts.volumemapper.runtime

import android.content.Context
import androidx.annotation.StringRes

data class LocalizedText(
    @param:StringRes val resourceId: Int,
    val arguments: List<Any> = emptyList(),
)

fun localizedText(
    @StringRes resourceId: Int,
    vararg arguments: Any,
): LocalizedText = LocalizedText(resourceId, arguments.toList())

fun Context.resolveLocalizedText(text: LocalizedText): String {
    val resolvedArguments = text.arguments.map { argument ->
        if (argument is LocalizedText) resolveLocalizedText(argument) else argument
    }.toTypedArray()
    return getString(text.resourceId, *resolvedArguments)
}
