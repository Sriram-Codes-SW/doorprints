package app.doorprints.drive.auth

/** Why the Drive token could not be had (web: `SignInError`). Never carries a token; the message is the kind. */
class SignInException(val kind: Kind) : Exception(kind.name) {
    enum class Kind {
        /** Nothing was granted, or the person unticked the Drive permission (granular consent): fail closed. */
        DENIED,

        /** The person closed the prompt without answering: ask again later, do not say "refused". */
        CANCELLED,

        /** Google needs a consent screen and nobody is there to show it (a background run): connect again from the app. */
        CONSENT_REQUIRED,

        OFFLINE,

        /** Google Play services missing, out of date or not answering. */
        UNAVAILABLE,
    }
}
