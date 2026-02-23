oc get application | awk 'NR>1''{print $1}'> apps.txt
while IFS= read -r line; do
  echo "Row: $line"
  if [[ $line == *[0-9] ]]; then
  oc delete application $line
  else
    echo "Does not end with a number"
  fi
done < apps.txt
rm -rf apps.txt
