param(
    [string]$ListFile = "functions-list.txt"
)
$raw = Get-Content $ListFile -Raw
$names = @(
    'updateStudentProfile','unassignStudentFromBatch','hardRemoveStudentFromBatch',
    'commitFinancialOperation','createExamWithFees','loginStaff','provisionStudentAccount',
    'uploadSecureMedia','getSecureMediaUrl','permanentlyPurgeInstitute','permanentlyPurgeBatch',
    'reconcileStudentOperationalSummary','reconcileBatchOperationalSummary','reconcileStaffOperationalSummary',
    'expireElapsedSubscriptions','cleanupInstituteOwnerLoginActivity','recordInstituteOwnerLogin',
    'getInstituteOwnerLoginActivity','recordStudentActivity','getStudentActivity',
    'createEntitledBatch','createEntitledStudent','createEntitledStaff','shiftStudentBetweenBatches',
    'updateStaffAccount','provisionStaffAccount','createRegistrationProfile','loginStudent',
    'disableStudentAccount','deleteStudentAccount','getStudentAccountStatus',
    'commitSafeDeletion','commitSubscriptionOperation','commitPlatformAdminOperation',
    'repairSubscriptionEntitlements','permanentlyPurgeStudent','permanentlyPurgeStaff','submitPublicRegistration'
)
foreach ($fn in $names) {
    $idx = $raw.IndexOf('functions/' + $fn + '"')
    if ($idx -ge 0) {
        $chunk = $raw.Substring($idx, [Math]::Min(4000, $raw.Length - $idx))
        $rev = [regex]::Match($chunk, 'revision.:.([^,"]+)')
        $upd = [regex]::Match($chunk, 'updateTime.:.([^,"]+)')
        $hash = [regex]::Match($chunk, 'firebase-functions-hash.:.([^,"]+)')
        $state = [regex]::Match($chunk, 'state.:.([^,"]+)')
        Write-Output ("{0} | rev={1} | state={2} | upd={3} | hash={4}" -f $fn, $rev.Groups[1].Value, $state.Groups[1].Value, $upd.Groups[1].Value, $hash.Groups[1].Value)
    } else {
        Write-Output ("{0} | NOT DEPLOYED" -f $fn)
    }
}
