package proj.dnd

/**
 * Represents metadata for a Foundry-like module definition, parsed from a remote `module.json`.
 *
 * This data class provides a lightweight, serializable model that maps directly to
 * module manifest fields. It is used by [ModuleManager] to identify and download
 * module archives.
 *
 * ### Typical fields
 * - **id** – Internal identifier for the module.
 *   Used for local naming, caching, and extraction directories.
 * - **name** – Optional display name of the module.
 *   Used as a fallback identifier if `id` is missing.
 * - **title** – Human-readable module title.
 *   Usually the full descriptive name as shown in the Foundry module list.
 * - **download** – Optional URL pointing to the downloadable ZIP archive of the module.
 *
 * ### Example
 * ```json
 * {
 *   "id": "awesome-tools",
 *   "name": "Awesome Tools",
 *   "title": "Awesome Tools for Foundry",
 *   "download": "https://github.com/user/repo/releases/latest/download/module.zip"
 * }
 * ```
 *
 * @property id Internal unique identifier of the module (may be `null`).
 * @property name Optional short name of the module (may be `null`).
 * @property title Descriptive module title displayed to the user.
 * @property download Optional direct link to the module archive.
 */
data class ModuleData(
    val id: String? = null,
    val name: String? = null,
    val title: String,
    val download: String? = null
)