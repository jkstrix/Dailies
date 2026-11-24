#!/usr/bin/env bash
# Publishes a GitHub release's jars to Modrinth and CurseForge, each to its own project as .github/publish.json says.
#
#   publish.sh <tag>        the tag is v<version>+<minecraft>, e.g. v1.3.0+26.3
#
# Needs GH_TOKEN (to read the release), MODRINTH_TOKEN (a Modrinth personal access token that may create versions)
# and CURSEFORGE_TOKEN (a CurseForge API token). DRY_RUN=1 says what it would publish and uploads nothing.
#
# A version already on Modrinth is left alone, so running it again only adds what is missing there. CurseForge has
# no such check, so a jar goes to CurseForge only when it was new on Modrinth in this run; CURSEFORGE_ALL=1 sends every
# jar to CurseForge regardless, for filling it in for a release that is already out. ONLY=dailies,companion publishes
# just those jars.
set -euo pipefail

tag="${1:?the release tag, e.g. v1.3.0+26.3}"
repo="${GITHUB_REPOSITORY:-jkstrix/Dailies}"
config="$(cd "$(dirname "$0")/.." && pwd)/publish.json"
modrinth="https://api.modrinth.com/v2"
curseforge="https://minecraft.curseforge.com/api"
agent="jkstrix/Dailies release workflow (github.com/jkstrix/Dailies)"
dry="${DRY_RUN:-0}"
cf_all="${CURSEFORGE_ALL:-0}"
# A comma-separated list of jar keys (dailies, companion, compat-jade, ...) to publish alone; empty for all
only=",${ONLY:-},"

if [[ ! "$tag" =~ ^v([^+]+)\+(.+)$ ]]; then
    echo "::error::the tag $tag is not v<version>+<minecraft>"
    exit 1
fi
dailies_version="${BASH_REMATCH[1]}"
minecraft="${BASH_REMATCH[2]}"
if [ "$dry" != 1 ] && [ -z "${MODRINTH_TOKEN:-}" ]; then
    echo "::error::MODRINTH_TOKEN is not set: add the Modrinth token as a repository secret of that name"
    exit 1
fi
if [ "$dry" != 1 ] && [ -z "${CURSEFORGE_TOKEN:-}" ]; then
    echo "::error::CURSEFORGE_TOKEN is not set: add the CurseForge API token as a repository secret of that name"
    exit 1
fi
auth=()
if [ -n "${MODRINTH_TOKEN:-}" ]; then
    auth=(-H "Authorization: $MODRINTH_TOKEN")
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
gh release download "$tag" --repo "$repo" --dir "$work" --pattern '*.jar'
release="$(gh release view "$tag" --repo "$repo" --json body,isPrerelease)"
version_type=release
if [ "$(jq -r .isPrerelease <<<"$release")" = true ]; then
    version_type=beta
fi
# Dailies' changelog is the release notes up to their download table, which lists GitHub's files, not the sites'
dailies_changelog="$(jq -r .body <<<"$release" | tr -d '\r' | awk '/^## What to download/ { exit } { print }')"

# CurseForge names its game versions by number: this Minecraft version, NeoForge, and the client and server sides
cf_versions=()
if [ -n "${CURSEFORGE_TOKEN:-}" ]; then
    curl -fsS -A "$agent" -H "X-Api-Token: $CURSEFORGE_TOKEN" "$curseforge/game/version-types" > "$work/cf-types.json"
    curl -fsS -A "$agent" -H "X-Api-Token: $CURSEFORGE_TOKEN" "$curseforge/game/versions" > "$work/cf-versions.json"
    cf_version() {
        jq -r --arg name "$1" --arg type "$2" --slurpfile types "$work/cf-types.json" '
            ($types[0] | map({(.id | tostring): .name}) | add) as $typeNames
            | [.[] | select(.name == $name and ($typeNames[(.gameVersionTypeID | tostring)] // "" | test($type)))][0].id // empty' "$work/cf-versions.json"
    }
    # The version type is "Minecraft 1.21" for 1.21.x and just "26.3" for 26.x
    cf_minecraft="$(cf_version "$minecraft" '^(Minecraft )?[0-9]')"
    cf_neoforge="$(cf_version NeoForge '^Modloader')"
    cf_client="$(cf_version Client '^Environment')"
    cf_server="$(cf_version Server '^Environment')"
    if [ -z "$cf_minecraft" ] || [ -z "$cf_neoforge" ]; then
        echo "::error::CurseForge has no game version for Minecraft $minecraft or NeoForge (got '$cf_minecraft' and '$cf_neoforge')"
        exit 1
    fi
    echo "CurseForge game versions: Minecraft $minecraft $cf_minecraft, NeoForge $cf_neoforge, client $cf_client, server $cf_server"
fi

# Sends one jar to CurseForge. A relation CurseForge refuses (a project it has not approved yet, or a slug it does not
# know) is left out and the file sent again, as many times as it takes, with a warning to add it by hand later
curseforge_upload() {
    local jar="$1" file="$2" cf_project="$3" meta="$4"
    local out="$work/cf-out.json" code slug
    for attempt in 1 2 3 4 5 6 7 8 9 10; do
        code="$(curl -sS -o "$out" -w '%{http_code}' -A "$agent" -H "X-Api-Token: $CURSEFORGE_TOKEN"             -F "metadata=<$meta;type=application/json" -F "file=@$jar;filename=$file" "$curseforge/projects/$cf_project/upload-file" || true)"
        if [ "$code" = 200 ]; then
            echo "$file: on CurseForge as file $(jq -r .id "$out")"
            return 0
        fi
        echo "$file: CurseForge said $code: $(head -c 400 "$out")"
        slug="$(jq -r '.errorMessage // ""' "$out" 2>/dev/null | sed -n "s/^Invalid slug in project relations: '\([^']*\)'.*/\1/p")"
        if [ -z "$slug" ] || [ "$(jq --arg s "$slug" '[.relations.projects[]? | select(.slug == $s)] | length' "$meta")" = 0 ]; then
            return 1
        fi
        echo "::warning::$file: CurseForge does not know '$slug' yet; sent without that relation, so add it on the file's page later"
        jq --arg s "$slug" '.relations.projects |= map(select(.slug != $s)) | if (.relations.projects | length) == 0 then del(.relations) else . end'             "$meta" > "$meta.next" && mv "$meta.next" "$meta"
    done
    return 1
}

published=0
uploaded=0
failed=0
for jar in "$work"/*.jar; do
    file="$(basename "$jar")"
    stem="${file%.jar}"
    version="${stem##*-}"
    rest="${stem%-*}"
    if [[ "$rest" != *"-$minecraft" ]]; then
        echo "$file: not for one Minecraft version (the API jar); left on GitHub"
        continue
    fi
    key="${rest%-"$minecraft"}"
    key="${key#dailies}"
    key="${key#-}"
    key="${key:-dailies}"
    if [ "$only" != ",," ] && [[ "$only" != *",$key,"* ]]; then
        continue
    fi
    entry="$(jq -c --arg key "$key" '.[$key] // empty' "$config")"
    if [ -z "$entry" ]; then
        echo "::warning::$file: publish.json has no project for '$key'; left on GitHub"
        continue
    fi
    project="$(jq -r .project <<<"$entry")"
    title="$(jq -r .title <<<"$entry")"
    number="$version+$minecraft"
    name="$title $version for Minecraft $minecraft"
    if [ "$key" = dailies ]; then
        changelog="$dailies_changelog"
    else
        changelog="Released with Dailies $dailies_version for Minecraft $minecraft."
    fi

    # Modrinth
    new_on_modrinth=0
    on_modrinth=0
    if versions="$(curl -fsS -A "$agent" "${auth[@]}" "$modrinth/project/$project/version")"; then
        if [ "$(jq --arg n "$number" '[.[] | select(.version_number == $n)] | length' <<<"$versions")" != 0 ]; then
            on_modrinth=1
            echo "$file: $title $number is already on Modrinth"
        fi
    elif [ "$dry" = 1 ]; then
        echo "$file: could not list $title's versions (a project under review needs the token to be seen)"
    else
        echo "::error::$file: could not list $title's versions on Modrinth"
        exit 1
    fi
    if [ "$on_modrinth" = 0 ]; then
        jq -n --arg name "$name" --arg number "$number" --arg changelog "$changelog" \
            --arg minecraft "$minecraft" --arg type "$version_type" --arg project "$project" --argjson entry "$entry" '{
                name: $name, version_number: $number, changelog: $changelog, game_versions: [$minecraft],
                version_type: $type, loaders: ["neoforge"], featured: false, status: "listed", project_id: $project,
                dependencies: [($entry.dependencies // [])[] | select((.minecraft // [$minecraft]) | index($minecraft))
                    | {project_id: .project, dependency_type: .type}],
                file_parts: ["file"], primary_file: "file"
            }' > "$work/data.json"
        if [ "$dry" = 1 ]; then
            echo "$file: would publish $title $number on Modrinth"
            jq -c '{name, version_number, version_type, game_versions, dependencies}' "$work/data.json"
        else
            curl -sS --fail-with-body -A "$agent" "${auth[@]}" \
                -F "data=<$work/data.json;type=application/json" -F "file=@$jar;filename=$file" "$modrinth/version" \
                | jq -r '"\(.name): published on Modrinth as version \(.id)"'
            published=$((published + 1))
        fi
        new_on_modrinth=1
    fi

    # CurseForge
    cf_project="$(jq -r '.curseforge // empty' <<<"$entry")"
    if [ -z "$cf_project" ]; then
        continue
    fi
    if [ "$new_on_modrinth" = 0 ] && [ "$cf_all" != 1 ]; then
        echo "$file: not new on Modrinth, so taken to be on CurseForge already (CURSEFORGE_ALL=1 sends it anyway)"
        continue
    fi
    side="$(jq -r '.side // "client"' <<<"$entry")"
    side_version="$cf_client"
    if [ "$side" = server ]; then
        side_version="$cf_server"
    fi
    jq -n --arg name "$name" --arg changelog "$changelog" --arg minecraft "$minecraft" --arg type "$version_type" \
        --arg mc "${cf_minecraft:-0}" --arg neo "${cf_neoforge:-0}" --arg side "${side_version:-}" --argjson entry "$entry" '{
            displayName: $name, changelog: $changelog, changelogType: "markdown", releaseType: $type,
            gameVersions: ([$mc, $neo, $side] | map(select(. != "" and . != "0") | tonumber)),
            relations: {projects: [($entry.relations // [])[] | select((.minecraft // [$minecraft]) | index($minecraft))
                | {slug: .slug, type: .type}]}
        } | if (.relations.projects | length) == 0 then del(.relations) else . end' > "$work/cf-meta.json"
    if [ "$dry" = 1 ]; then
        echo "$file: would send $name to CurseForge project $cf_project"
        jq -c '{displayName, releaseType, gameVersions, relations}' "$work/cf-meta.json"
        continue
    fi
    if curseforge_upload "$jar" "$file" "$cf_project" "$work/cf-meta.json"; then
        uploaded=$((uploaded + 1))
    else
        echo "::error::$file: CurseForge did not take it"
        failed=$((failed + 1))
    fi
done
echo "Published $published version(s) to Modrinth and $uploaded file(s) to CurseForge for $tag"
if [ "$failed" != 0 ]; then
    exit 1
fi
