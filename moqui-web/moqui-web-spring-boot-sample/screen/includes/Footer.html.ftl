<#assign STATIC_PREFIX="/static"/>
${sri.getAfterScreenWriterText()}

<#-- Footer JavaScript -->
<#list footer_scripts?if_exists as scriptLocation>
    <script src="${STATIC_PREFIX}${scriptLocation}"></script>
</#list>
<#assign scriptText = sri.getScriptWriterText()>
<#if scriptText?has_content>
    <script>
    ${scriptText}
    $(window).on('unload', function(){}); // Does nothing but break the bfcache
    </script>
</#if>
</body>
</html>
