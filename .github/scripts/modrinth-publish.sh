#!/usr/bin/env bash
# Publishes a GitHub release's jars to Modrinth, each to its own project as .github/modrinth.json says.
#
#   modrinth-publish.sh <tag>        the tag is v<version>+<minecraft>, e.g. v1.3.0+26.3
#
# Needs GH_TOKEN (to read the release) and MODRINTH_TOKEN (a Modrinth personal access token that may create
# versions). DRY_RUN=1 says what it would publish and uploads nothing. A version already on Modrinth is left alone,
# so running it again only adds what is missing.
set -euo pipefail

tag="${1:?the release tag, e.g. v1.3.0+26.3}"
repo="${GITHUB_REPOSITORY:-jkstrix/Dailies}"
config="$(cd "$(dirname "$0")/.." && pwd)/modrinth.json"
api="https://api.modrinth.com/v2"
agent="jkstrix/Dailies release workflow (github.com/jkstrix/Dailies)"
dry="${DRY_RUN:-0}"

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
# Dailies' changelog is the release notes up to their download table, which lists GitHub's files, not Modrinth's
dailies_changelog="$(jq -r .body <<<"$release" | tr -d '\r' | awk '/^## What to download/ { exit } { print }')"

published=0
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
    entry="$(jq -c --arg key "$key" '.[$key] // empty' "$config")"
    if [ -z "$entry" ]; then
        echo "::warning::$file: modrinth.json has no project for '$key'; left on GitHub"
        continue
    fi
    project="$(jq -r .project <<<"$entry")"
    title="$(jq -r .title <<<"$entry")"
    number="$version+$minecraft"

    if versions="$(curl -fsS -A "$agent" "${auth[@]}" "$api/project/$project/version")"; then
        if [ "$(jq --arg n "$number" '[.[] | select(.version_number == $n)] | length' <<<"$versions")" != 0 ]; then
            echo "$file: $title $number is already on Modrinth"
            continue
        fi
    elif [ "$dry" = 1 ]; then
        echo "$file: could not list $title's versions (a project under review needs the token to be seen)"
    else
        echo "::error::$file: could not list $title's versions on Modrinth"
        exit 1
    fi

    if [ "$key" = dailies ]; then
        changelog="$dailies_changelog"
    else
        changelog="Released with Dailies $dailies_version for Minecraft $minecraft."
    fi
    jq -n --arg name "$title $version for Minecraft $minecraft" --arg number "$number" --arg changelog "$changelog" \
        --arg minecraft "$minecraft" --arg type "$version_type" --arg project "$project" --argjson entry "$entry" '{
            name: $name, version_number: $number, changelog: $changelog, game_versions: [$minecraft],
            version_type: $type, loaders: ["neoforge"], featured: false, status: "listed", project_id: $project,
            dependencies: [($entry.dependencies // [])[] | select((.minecraft // [$minecraft]) | index($minecraft))
                | {project_id: .project, dependency_type: .type}],
            file_parts: ["file"], primary_file: "file"
        }' > "$work/data.json"

    if [ "$dry" = 1 ]; then
        echo "$file: would publish $title $number"
        jq -c '{name, version_number, version_type, game_versions, dependencies}' "$work/data.json"
        continue
    fi
    curl -sS --fail-with-body -A "$agent" "${auth[@]}" \
        -F "data=<$work/data.json;type=application/json" -F "file=@$jar;filename=$file" "$api/version" \
        | jq -r '"\(.name): published as version \(.id)"'
    published=$((published + 1))
done
echo "Published $published version(s) to Modrinth for $tag"
