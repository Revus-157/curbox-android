package neth.iecal.curbox.data.models

/** Bounded API-owned targets are independent from editable personal groups. */
data class ManagedGroupPolicy(
    val id: String = "",
    val kind: String = "",
    val name: String = "",
    val members: List<String> = emptyList(),
    val expiresAtMs: Long = 0,
    val ownerPackage: String = "",
)

const val MANAGED_GROUP_PREFIX = "api-managed:"

fun replaceManagedPolicies(base: Settings, requested: List<ManagedGroupPolicy>, owner: String, nowMs: Long): Settings {
    require(owner.isNotBlank() && owner.length<=200)
    require(requested.size<=32 && requested.map { it.id }.distinct().size==requested.size)
    requested.forEach {
        require(it.id.matches(Regex("[A-Za-z0-9_.-]{1,80}")))
        require(it.kind in setOf("app_group","keyword_group"))
        require(it.name.isNotBlank() && it.name.length<=100)
        require(it.members.size in 1..100 && it.members.distinct().size==it.members.size)
        require(it.members.all { member -> member.isNotBlank() && member.length<=200 })
        require(it.expiresAtMs>nowMs && it.expiresAtMs<=nowMs+26*60*60*1000L)
    }
    return base.copy(managedGroupPolicies=base.managedGroupPolicies.filter { it.ownerPackage!=owner }+
        requested.map { it.copy(ownerPackage=owner) })
}

fun effectiveManagedSettings(base: Settings, nowMs: Long): Settings {
    val active=base.managedGroupPolicies.filter { it.expiresAtMs > nowMs }
    if (active.isEmpty()) return base
    val hard=ScheduledUsageConfig(schedule=AppTimeConfig.allDay(),usage=AppUsageConfig(uniformLimit=0))
    val apps=active.filter { it.kind=="app_group" }.map {
        AppGroup(id=MANAGED_GROUP_PREFIX+it.ownerPackage+":"+it.id,name=it.name+" (managed)",selectedPackages=it.members,
            config=hard,isActive=true,
            warningScreenConfig=AppBlockerWarningScreenConfig(isProceedDisabled=true))
    }
    val keywords=active.filter { it.kind=="keyword_group" }.map {
        KeywordGroup(id=MANAGED_GROUP_PREFIX+it.ownerPackage+":"+it.id,name=it.name+" (managed)",selectedKeywords=it.members,
            config=hard,isActive=true,
            warningScreenConfig=AppBlockerWarningScreenConfig(isProceedDisabled=true))
    }
    return base.copy(
        blockedAppGroups=base.blockedAppGroups.filterNot { it.id.startsWith(MANAGED_GROUP_PREFIX) }+apps,
        keywordBlockerConfig=base.keywordBlockerConfig.copy(
            isActive=base.keywordBlockerConfig.isActive || keywords.isNotEmpty(),
            keywordGroups=base.keywordBlockerConfig.keywordGroups.filterNot { it.id.startsWith(MANAGED_GROUP_PREFIX) }+keywords),
        isWebsiteUsageTrackingEnabled=base.isWebsiteUsageTrackingEnabled || keywords.isNotEmpty(),
    )
}
