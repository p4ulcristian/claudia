# Sourced by run.sh, install.sh and guard.sh (bash 3.2): reads a Claudia env file
# (~/.config/claudia/env) the way systemd's EnvironmentFile= does on Linux, so one file format
# serves both: KEY=value lines, # or ; comments, a value in "double" or 'single' quotes taken
# without them. Nothing in it is expanded or run (it is never sourced itself).

# load_env <file>: exports every KEY=value of the file into this shell.
load_env() {
	local file=$1 line key value
	[ -r "$file" ] || { echo "$file is missing (deploy/macos/env.example)" >&2; return 1; }
	while IFS= read -r line || [ -n "$line" ]; do
		line=${line#"${line%%[![:space:]]*}"}
		line=${line%"${line##*[![:space:]]}"}
		case $line in '' | '#'* | ';'*) continue ;; esac
		key=${line%%=*}
		value=${line#*=}
		if [ "$key" = "$line" ] || ! [[ $key =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
			echo "$file: not KEY=value, skipped: $line" >&2
			continue
		fi
		case $value in
		\"*\") value=${value#\"}; value=${value%\"} ;;
		\'*\') value=${value#\'}; value=${value%\'} ;;
		esac
		export "$key=$value"
	done <"$file"
}
