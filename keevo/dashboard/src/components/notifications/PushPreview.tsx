"use client";

export const PREVIEW_TITLE_PLACEHOLDER = "Titre de l'annonce";
export const PREVIEW_BODY_PLACEHOLDER = "Message de l'annonce";

/** Android-style and iOS-style previews of the push as merchants will see it. */
export function PushPreview({ title, body }: { title: string; body: string }) {
  const t = title.trim() || PREVIEW_TITLE_PLACEHOLDER;
  const b = body.trim() || PREVIEW_BODY_PLACEHOLDER;

  return (
    <div className="space-y-3">
      <p className="text-xs font-semibold uppercase tracking-wider text-gray-500">Aperçu</p>

      <section aria-label="Aperçu Android" className="bg-gray-800 rounded-xl p-3">
        <div className="bg-white rounded-lg px-3 py-2 shadow">
          <div className="flex items-center gap-2 text-[11px] text-gray-500">
            <span className="inline-block w-3 h-3 rounded-full bg-blue-600" aria-hidden />
            <span>Keevo</span>
            <span aria-hidden>·</span>
            <span>maintenant</span>
          </div>
          <p className="mt-1 text-sm font-semibold text-gray-900 break-words">{t}</p>
          <p className="text-sm text-gray-700 break-words">{b}</p>
        </div>
      </section>

      <section aria-label="Aperçu iOS" className="bg-gradient-to-b from-sky-200 to-indigo-200 rounded-xl p-3">
        <div className="bg-white/80 backdrop-blur rounded-2xl px-3 py-2 shadow">
          <div className="flex items-center justify-between text-[11px] text-gray-500">
            <span className="flex items-center gap-1.5">
              <span className="inline-block w-4 h-4 rounded-md bg-blue-600" aria-hidden />
              <span className="uppercase tracking-wide">Keevo</span>
            </span>
            <span>maintenant</span>
          </div>
          <p className="mt-1 text-sm font-semibold text-gray-900 break-words">{t}</p>
          <p className="text-sm text-gray-800 break-words">{b}</p>
        </div>
      </section>
    </div>
  );
}
